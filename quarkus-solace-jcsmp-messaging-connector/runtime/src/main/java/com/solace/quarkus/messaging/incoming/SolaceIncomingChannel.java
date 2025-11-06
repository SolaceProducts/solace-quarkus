package com.solace.quarkus.messaging.incoming;

import static com.solace.quarkus.messaging.i18n.SolaceExceptions.ex;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.quarkus.messaging.SolaceConnectorIncomingConfiguration;
import com.solace.quarkus.messaging.converters.SolaceMessageUtils;
import com.solace.quarkus.messaging.fault.*;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.tracing.SolaceOpenTelemetryInstrumenter;
import com.solace.quarkus.messaging.tracing.SolaceTrace;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Queue;

import io.opentelemetry.api.OpenTelemetry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.smallrye.reactive.messaging.health.HealthReport;
import io.vertx.core.impl.VertxInternal;
import io.vertx.mutiny.core.Context;
import io.vertx.mutiny.core.Vertx;

public class SolaceIncomingChannel {

    private final String channel;
    private final Context context;
    private final SolaceAckHandler ackHandler;
    private final SolaceFailureHandler failureHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private FlowReceiver receiver;
    private Flow.Publisher<? extends Message<?>> stream;
    private final ExecutorService pollerThread;
    private final boolean gracefulShutdown;
    private final long gracefulShutdownWaitTimeout;
    private final List<Throwable> failures = new ArrayList<>();
    private SolaceOpenTelemetryInstrumenter solaceOpenTelemetryInstrumenter;
    private volatile JCSMPSession solace;

    // Assuming we won't ever exceed the limit of an unsigned long...
    private final IncomingMessagesUnsignedCounterBarrier unacknowledgedMessageTracker = new IncomingMessagesUnsignedCounterBarrier();

    public SolaceIncomingChannel(Vertx vertx, Instance<OpenTelemetry> openTelemetryInstance,
            SolaceConnectorIncomingConfiguration ic, JCSMPSession solace) {
        this.solace = solace;
        this.channel = ic.getChannel();
        this.context = Context.newInstance(((VertxInternal) vertx.getDelegate()).createEventLoopContext());
        this.gracefulShutdown = ic.getClientGracefulShutdown();
        this.gracefulShutdownWaitTimeout = ic.getClientGracefulShutdownWaitTimeout();
        XMLMessage.Outcome[] outcomes = new XMLMessage.Outcome[] { XMLMessage.Outcome.ACCEPTED };
        if (ic.getConsumerQueueSupportsNacks()) {
            outcomes = new XMLMessage.Outcome[] { XMLMessage.Outcome.ACCEPTED, XMLMessage.Outcome.FAILED,
                    XMLMessage.Outcome.REJECTED };
        }

        EndpointProperties endpointProperties = new EndpointProperties();
        Queue queue = getQueue(ic, this.solace, endpointProperties);

        ConsumerFlowProperties flow_prop = new ConsumerFlowProperties();
        flow_prop.setEndpoint(queue);
        flow_prop.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);
        flow_prop.addRequiredSettlementOutcomes(outcomes);
        ic.getConsumerQueueSelectorQuery().ifPresent(flow_prop::setSelector);
        ic.getConsumerQueueReplayStrategy().ifPresent(s -> {
            switch (s) {
                case "all-messages":
                    flow_prop.setReplayStartLocation(JCSMPFactory.onlyInstance().createReplayStartLocationBeginning());
                    break;
                case "time-based":
                    flow_prop.setReplayStartLocation(getTimeBasedReplayStrategy(ic));
                    break;
                case "replication-group-message-id":
                    flow_prop.setReplayStartLocation(getGroupMessageIdReplayStrategy(ic));
                    break;
            }
        });
        switch (ic.getConsumerQueueMissingResourceCreationStrategy()) {
            case "create-on-start":
                try {
                    solace.provision(queue, endpointProperties, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
                } catch (JCSMPException e) {
                    throw new RuntimeException(e);
                }
                break;
            case "do-not-create":
                break;
        }

        if (ic.getConsumerQueueAddAdditionalSubscriptions()) {
            String subscriptions = ic.getConsumerSubscriptions().orElse(this.channel);

            String[] subscriptionsArray = subscriptions.split(",");
            List<Uni<Void>> subscriptionUnis = new ArrayList<>();
            for (String subscription : subscriptionsArray) {
                Uni<Void> subUni = subscribeToTopic(solace, queue, subscription)
                        .onFailure().invoke(this::reportFailure);

                subscriptionUnis.add(subUni);
            }

            // Wait for all subscriptions to complete
            Uni.join().all(subscriptionUnis).andCollectFailures()
                    .await().indefinitely();
        }

        boolean lazyStart = ic.getClientLazyStart();
        this.ackHandler = new SolaceAckHandler();
        this.failureHandler = createFailureHandler(ic, solace);

        // TODO Here use a subscription receiver.receiveAsync with an internal queue
        this.pollerThread = Executors.newSingleThreadExecutor();

        this.receiver = createReceiver(solace, flow_prop, endpointProperties)
                .onFailure().invoke(this::reportFailure).await().indefinitely();

        Multi<? extends Message<?>> incomingMulti = Multi.createBy().repeating()
                .uni(() -> Uni.createFrom().item(() -> {
                    try {
                        return receiver.receive();
                    } catch (JCSMPException e) {
                        throw new RuntimeException(e);
                    }
                })
                        .runSubscriptionOn(pollerThread))
                .until(__ -> closed.get())
                .emitOn(context::runOnContext)
                .map(consumed -> new SolaceInboundMessage<>(consumed, ackHandler, failureHandler,
                        unacknowledgedMessageTracker, this::reportFailure));

        if (ic.getClientTracingEnabled()) {
            solaceOpenTelemetryInstrumenter = SolaceOpenTelemetryInstrumenter
                    .createForIncoming(openTelemetryInstance);
            incomingMulti = incomingMulti.map(message -> {
                BytesXMLMessage consumedMessage = message.getMetadata(SolaceInboundMetadata.class).get()
                        .getMessage();
                Map<String, String> messageProperties = new HashMap<>();

                messageProperties.put("messaging.solace.replication_group_message_id",
                        consumedMessage.getReplicationGroupMessageId().toString());
                messageProperties.put("messaging.solace.priority", Integer.toString(consumedMessage.getPriority()));
                if (consumedMessage.getProperties() != null && !consumedMessage.getProperties().isEmpty()) {
                    messageProperties.putAll(SolaceMessageUtils.getPropertiesMap(consumedMessage.getProperties()));
                }

                SolaceTrace solaceTrace = null;
                try {
                    solaceTrace = new SolaceTrace.Builder()
                            .withDestinationKind("queue")
                            .withTopic(consumedMessage.getDestination().getName())
                            .withMessageID(consumedMessage.getApplicationMessageId())
                            .withCorrelationID(consumedMessage.getCorrelationId())
                            .withPartitionKey(consumedMessage.getProperties() != null ? (consumedMessage.getProperties()
                                    .containsKey(
                                            XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                                    ? consumedMessage.getProperties()
                                                            .getString(
                                                                    XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                                    : null)
                                    : null)
                            .withPayloadSize((long) SolaceMessageUtils.getPayloadAsBytes(consumedMessage).length)
                            .withProperties(messageProperties)
                            .build();
                } catch (SDTException e) {
                    throw new RuntimeException(e);
                }

                return solaceOpenTelemetryInstrumenter.traceIncoming(message, solaceTrace, true);
            });
        } else {
            solaceOpenTelemetryInstrumenter = null;
        }

        this.stream = incomingMulti.plug(m -> lazyStart
                ? m.onSubscription()
                        .call(() -> startReceiver(receiver))
                : m)
                .onItem().invoke(() -> alive.set(true))
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(3).onFailure()
                .invoke(this::reportFailure);

        if (!lazyStart) {
            startReceiver(receiver).await().indefinitely();
        }
    }

    private synchronized void reportFailure(Throwable throwable) {
        alive.set(false);
        // Don't keep all the failures, there are only there for reporting.
        if (failures.size() == 10) {
            failures.remove(0);
        }
        failures.add(throwable);
    }

    private SolaceFailureHandler createFailureHandler(SolaceConnectorIncomingConfiguration ic, JCSMPSession solace) {
        String strategy = ic.getConsumerFailureStrategy();
        SolaceFailureHandler.Strategy actualStrategy = SolaceFailureHandler.Strategy.from(strategy);
        switch (actualStrategy) {
            case IGNORE:
                return new SolaceIgnoreFailure(ic.getChannel());
            case FAIL:
                return new SolaceFail(ic.getChannel());
            case DISCARD:
                return new SolaceDiscard(ic.getChannel());
            case ERROR_TOPIC:
                if (ic.getConsumerErrorTopic().isEmpty()) {
                    throw ex.illegalArgumentInvalidFailureStrategy(strategy);
                }
                return new SolaceErrorTopic(ic.getChannel(), ic.getConsumerErrorTopic().get(),
                        ic.getConsumerErrorMessageDmqEligible(), ic.getConsumerErrorMessageTtl().orElse(null),
                        ic.getConsumerErrorMessageMaxDeliveryAttempts(), solace);
            default:
                throw ex.illegalArgumentInvalidFailureStrategy(strategy);
        }

    }

    private Uni<Void> subscribeToTopic(JCSMPSession session, Queue queue, String subscription) {
        return Uni.createFrom().item(Unchecked.supplier(() -> {
            try {
                Topic topic = JCSMPFactory.onlyInstance().createTopic(subscription);
                session.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM);
                return Uni.createFrom().voidItem();
            } catch (JCSMPException e) {
                SolaceLogging.log.errorf("Failed to subscribe topic %s on queue %s for channel %s", subscription, queue,
                        channel, e);
                throw new RuntimeException(e);
            }
        })).replaceWithVoid();
    }

    private Uni<FlowReceiver> createReceiver(JCSMPSession solace, ConsumerFlowProperties flowProperties,
            EndpointProperties endpointProperties) {
        return Uni.createFrom().item(Unchecked.supplier(() -> {
            try {
                return solace.createFlow(null, flowProperties, endpointProperties);
            } catch (JCSMPException e) {
                SolaceLogging.log.errorf("Failed to create flow receiver for solace queue on channel %s", channel, e);
                throw new RuntimeException(e);
            }
        }));
    }

    private Uni<Void> startReceiver(FlowReceiver receiver) {
        return Uni.createFrom().item(Unchecked.supplier(() -> {
            try {
                receiver.start();
                return Uni.createFrom().voidItem();
            } catch (JCSMPException e) {
                SolaceLogging.log.errorf(e, "Failed to start Solace consumer for channel %s", channel);
                throw new RuntimeException(e);
            }
        })).replaceWithVoid();
    }

    private Uni<SolaceTrace> initializeSolaceTracing(BytesXMLMessage consumedMessage, Map<String, String> messageProperties) {
        return Uni.createFrom().item(() -> {
            SolaceTrace solaceTrace = null;
            try {
                solaceTrace = new SolaceTrace.Builder()
                        .withDestinationKind("queue")
                        .withTopic(consumedMessage.getDestination().getName())
                        .withMessageID(consumedMessage.getApplicationMessageId())
                        .withCorrelationID(consumedMessage.getCorrelationId())
                        .withPartitionKey(
                                consumedMessage.getProperties()
                                        .containsKey(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                                ? consumedMessage.getProperties()
                                                        .getString(
                                                                XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                                : null)
                        .withPayloadSize((long) SolaceMessageUtils.getPayloadAsBytes(consumedMessage).length)
                        .withProperties(messageProperties)
                        .build();

                return solaceTrace;
            } catch (SDTException e) {
                SolaceLogging.log.errorf("Failed to initialize Solace Trace for message %s received on channel %s",
                        consumedMessage.getReplicationGroupMessageId().toString(), channel, e);
                throw new RuntimeException(e);
            }
        });
    }

    private static Queue getQueue(SolaceConnectorIncomingConfiguration ic, JCSMPSession solace,
            EndpointProperties endpointProperties) {
        String queueType = ic.getConsumerQueueType();
        switch (queueType) {
            case "durable-non-exclusive":
                endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_NONEXCLUSIVE);
                return JCSMPFactory.onlyInstance().createQueue(ic.getConsumerQueueName().orElse(ic.getChannel()));
            case "non-durable-exclusive":
                endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
                try {
                    return solace.createTemporaryQueue(ic.getConsumerQueueName().orElse(ic.getChannel()));
                } catch (JCSMPException e) {
                    throw new RuntimeException(e);
                }
            case "durable-exclusive":
                endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
                return JCSMPFactory.onlyInstance().createQueue(ic.getConsumerQueueName().orElse(ic.getChannel()));
            default:
                throw ex.illegalArgumentInvalidFailureStrategy(queueType);

        }
    }

    private ReplicationGroupMessageId getGroupMessageIdReplayStrategy(SolaceConnectorIncomingConfiguration ic) {
        String groupMessageId = ic.getConsumerQueueReplayReplicationGroupMessageId().orElseThrow();
        try {
            return JCSMPFactory.onlyInstance().createReplicationGroupMessageId(groupMessageId);
        } catch (InvalidPropertiesException e) {
            SolaceLogging.log.errorf("Invalid replication group message id configured on channel %s", channel, e);
            throw new RuntimeException(e);
        }

    }

    private static ReplayStartLocationDate getTimeBasedReplayStrategy(SolaceConnectorIncomingConfiguration ic) {
        String zoneDateTime = ic.getConsumerQueueReplayTimebasedStartTime().orElseThrow();
        return JCSMPFactory.onlyInstance()
                .createReplayStartLocationDate(Date.from(ZonedDateTime.parse(zoneDateTime).toInstant()));
    }

    public Flow.Publisher<? extends Message<?>> getStream() {
        return this.stream;
    }

    public void waitForUnAcknowledgedMessages() {
        try {
            receiver.stop();
            SolaceLogging.log.infof("Waiting for incoming channel %s messages to be acknowledged", channel);
            if (!unacknowledgedMessageTracker.awaitEmpty(this.gracefulShutdownWaitTimeout, TimeUnit.MILLISECONDS)) {
                SolaceLogging.log.infof("Timed out while waiting for the" +
                        " remaining messages to be acknowledged on channel %s.", channel);
            }
        } catch (InterruptedException e) {
            SolaceLogging.log.infof("Interrupted while waiting for messages on channel %s to get acknowledged", channel);
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (this.gracefulShutdown) {
            waitForUnAcknowledgedMessages();
        }
        closed.compareAndSet(false, true);
        if (this.pollerThread != null) {
            if (this.gracefulShutdown) {
                this.pollerThread.shutdown();
                try {
                    this.pollerThread.awaitTermination(3000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    SolaceLogging.log.shutdownException(e.getMessage());
                    throw new RuntimeException(e);
                }
            } else {
                this.pollerThread.shutdownNow();
            }
        }
        receiver.close();
    }

    public void isStarted(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, !solace.isClosed());
    }

    public void isReady(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, !solace.isClosed() && receiver != null && !receiver.isClosed());
    }

    public void isAlive(HealthReport.HealthReportBuilder builder) {
        List<Throwable> reportedFailures;
        if (!failures.isEmpty()) {
            synchronized (this) {
                reportedFailures = new ArrayList<>(failures);
            }
            builder.add(channel, !solace.isClosed() && alive.get(),
                    reportedFailures.stream().map(Throwable::getMessage).collect(Collectors.joining()));
        } else {
            builder.add(channel, !solace.isClosed() && alive.get());
        }
    }
}
