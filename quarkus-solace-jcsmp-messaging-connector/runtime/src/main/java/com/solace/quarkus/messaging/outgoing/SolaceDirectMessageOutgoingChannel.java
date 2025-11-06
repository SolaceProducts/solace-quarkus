package com.solace.quarkus.messaging.outgoing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.quarkus.messaging.PublishReceipt;
import com.solace.quarkus.messaging.SolaceConnectorOutgoingConfiguration;
import com.solace.quarkus.messaging.converters.SolaceMessageUtils;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.tracing.SolaceOpenTelemetryInstrumenter;
import com.solace.quarkus.messaging.tracing.SolaceTrace;
import com.solacesystems.jcsmp.*;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.opentelemetry.api.OpenTelemetry;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.health.HealthReport;
import io.smallrye.reactive.messaging.providers.helpers.MultiUtils;
import io.vertx.core.json.Json;
import io.vertx.mutiny.core.Vertx;

public class SolaceDirectMessageOutgoingChannel {

    private final XMLMessageProducer publisher;
    private final String channel;
    private final Flow.Subscriber<? extends Message<?>> subscriber;
    private final Topic topic;
    private final SenderProcessor processor;
    private final boolean gracefulShutdown;
    private final long gracefulShutdownWaitTimeout;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final List<Throwable> failures = new ArrayList<>();
    private final SolaceOpenTelemetryInstrumenter solaceOpenTelemetryInstrumenter;
    private final JCSMPSession solace;
    private volatile boolean isPublisherReady = true;
    // Assuming we won't ever exceed the limit of an unsigned long...
    private final OutgoingMessagesUnsignedCounterBarrier publishedMessagesTracker = new OutgoingMessagesUnsignedCounterBarrier();

    public SolaceDirectMessageOutgoingChannel(Vertx vertx, Instance<OpenTelemetry> openTelemetryInstance,
            SolaceConnectorOutgoingConfiguration oc, JCSMPSession solace) {
        this.solace = solace;
        this.channel = oc.getChannel();
        //        DirectMessagePublisherBuilder builder = solace.createDirectMessagePublisherBuilder();
        //        switch (oc.getProducerBackPressureStrategy()) {
        //            case "wait":
        //                builder.onBackPressureWait(oc.getProducerBackPressureBufferCapacity());
        //                break;
        //            case "reject":
        //                builder.onBackPressureReject(oc.getProducerBackPressureBufferCapacity());
        //                break;
        //            default:
        //                builder.onBackPressureElastic();
        //                break;
        //        }
        this.gracefulShutdown = oc.getClientGracefulShutdown();
        this.gracefulShutdownWaitTimeout = oc.getClientGracefulShutdownWaitTimeout();
        try {
            this.publisher = this.solace.getMessageProducer(new PublishReceipt());
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }
        boolean lazyStart = oc.getClientLazyStart();
        this.topic = JCSMPFactory.onlyInstance().createTopic(oc.getProducerTopic().orElse(this.channel));
        if (oc.getClientTracingEnabled()) {
            solaceOpenTelemetryInstrumenter = SolaceOpenTelemetryInstrumenter.createForOutgoing(openTelemetryInstance);
        } else {
            solaceOpenTelemetryInstrumenter = null;
        }
        this.processor = new SenderProcessor(oc.getProducerMaxInflightMessages(), oc.getProducerWaitForPublishReceipt(),
                m -> sendMessage(solace, m, oc.getClientTracingEnabled()).onFailure()
                        .invoke(this::reportFailure));
        this.subscriber = MultiUtils.via(processor, multi -> multi.plug(
                m -> lazyStart ? m.onSubscription().call(() -> Uni.createFrom().voidItem()) : m));
        if (!lazyStart) {
            //            this.publisher.start();
        }

        // @TODO - Check if available in JCSMP
        //        this.publisher.setPublisherReadinessListener(() -> isPublisherReady = true);
        //        this.publisher.setPublishFailureListener(failedPublishEvent -> {
        //            SolaceLogging.log.error("Failed to publish direct message");
        //            reportFailure(failedPublishEvent.getException());
        //        });
    }

    private Uni<Void> sendMessage(JCSMPSession solace, Message<?> m, boolean isTracingEnabled) {

        // TODO - Use isPublisherReady to check if publisher is in ready state before publishing. This is required when back-pressure is set to reject. We need to block this call till isPublisherReady is true
        return publishMessage(publisher, m, isTracingEnabled)
                .onItem().transformToUni(receipt -> {
                    alive.set(true);
                    return Uni.createFrom().completionStage(m.getAck());
                })
                .onFailure().recoverWithUni(t -> {
                    reportFailure(t);
                    return Uni.createFrom().completionStage(m.nack(t));
                });
    }

    private synchronized void reportFailure(Throwable throwable) {
        alive.set(false);
        // Don't keep all the failures, there are only there for reporting.
        if (failures.size() == 10) {
            failures.remove(0);
        }
        failures.add(throwable);
    }

    private Uni<Object> publishMessage(XMLMessageProducer publisher, Message<?> m, boolean isTracingEnabled) {
        publishedMessagesTracker.increment();
        AtomicReference<Topic> topic = new AtomicReference<>(this.topic);
        BytesXMLMessage outboundMessage;
        Object payload = m.getPayload();
        if (payload instanceof BytesXMLMessage) {
            outboundMessage = JCSMPFactory.onlyInstance().createMessage((BytesXMLMessage) payload);
        } else {
            outboundMessage = JCSMPFactory.onlyInstance().createBytesXMLMessage();
        }
        SDTMap map = JCSMPFactory.onlyInstance().createMap();
        m.getMetadata(SolaceOutboundMetadata.class).ifPresent(metadata -> {
            //            if (metadata.getHttpContentHeaders() != null && !metadata.getHttpContentHeaders().isEmpty()) {
            //                metadata.getHttpContentHeaders().forEach(msgBuilder::withHTTPContentHeader);
            //            }
            if (metadata.getProperties() != null && !metadata.getProperties().isEmpty()) {
                //                metadata.getProperties().forEach(msgBuilder::withProperty);
                for (String key : metadata.getProperties().keySet()) {
                    try {
                        map.putString(key, metadata.getProperties().get(key));
                    } catch (SDTException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            if (metadata.getExpiration() != null) {
                outboundMessage.setExpiration(metadata.getExpiration());
            }
            if (metadata.getPriority() != null) {
                outboundMessage.setPriority(metadata.getPriority());
            }
            if (metadata.getSenderId() != null) {
                outboundMessage.setSenderId(metadata.getSenderId());
            }
            if (metadata.getApplicationMessageType() != null) {
                outboundMessage.setApplicationMessageType(metadata.getApplicationMessageType());
            }
            if (metadata.getTimeToLive() != null) {
                outboundMessage.setTimeToLive(metadata.getTimeToLive());
            }
            if (metadata.getApplicationMessageId() != null) {
                outboundMessage.setApplicationMessageId(metadata.getApplicationMessageId());
            }
            if (metadata.getClassOfService() != null) {
                outboundMessage.setCos(Arrays.stream(User_Cos.values()).filter(co -> co.value() == metadata.getClassOfService())
                        .findFirst().orElse(null));
            }
            if (metadata.getPartitionKey() != null) {
                try {
                    map.putString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY,
                            metadata.getPartitionKey());
                } catch (SDTException e) {
                    throw new RuntimeException(e);
                }
            }
            if (metadata.getCorrelationId() != null) {
                outboundMessage.setCorrelationId(metadata.getCorrelationId());
            }

            if (metadata.getDynamicDestination() != null) {
                topic.set(JCSMPFactory.onlyInstance().createTopic(metadata.getDynamicDestination()));
            }
        });
        if (!map.isEmpty()) {
            outboundMessage.setProperties(map);
        }

        if (payload instanceof String) {
            //            outboundMessage.setHTTPContentEncoding(HttpHeaderValues.TEXT_PLAIN.toString());
            outboundMessage.setHTTPContentType(HttpHeaderValues.TEXT_PLAIN.toString());
            outboundMessage.writeAttachment(((String) payload).getBytes(StandardCharsets.UTF_8));
        } else if (payload instanceof byte[]) {
            outboundMessage.writeAttachment((byte[]) payload);
        } else if (!(payload instanceof BytesXMLMessage)) {
            outboundMessage.setHTTPContentType(HttpHeaderValues.APPLICATION_JSON.toString());
            //            outboundMessage.setHTTPContentEncoding(HttpHeaderValues.APPLICATION_JSON.toString());
            outboundMessage.writeAttachment(Json.encode(payload).getBytes(StandardCharsets.UTF_8));
        }

        if (isTracingEnabled) {
            SolaceTrace solaceTrace = null;
            try {
                solaceTrace = new SolaceTrace.Builder()
                        .withDestinationKind("topic")
                        .withTopic(topic.get().getName())
                        .withMessageID(outboundMessage.getApplicationMessageId())
                        .withCorrelationID(outboundMessage.getCorrelationId())
                        .withPartitionKey(outboundMessage.getProperties() != null ? (outboundMessage.getProperties()
                                .containsKey(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                        ? outboundMessage.getProperties()
                                                .getString(
                                                        XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                        : null)
                                : null)
                        .withPayloadSize((long) SolaceMessageUtils.getPayloadAsBytes(outboundMessage).length)
                        .withProperties(SolaceMessageUtils.getPropertiesMap(outboundMessage.getProperties())).build();
            } catch (SDTException e) {
                throw new RuntimeException(e);
            }
            solaceOpenTelemetryInstrumenter.traceOutgoing(m, solaceTrace);
        }

        return Uni.createFrom().<Object> emitter(e -> {
            try {
                if (isPublisherReady) {
                    publisher.send(outboundMessage, topic.get());
                    publishedMessagesTracker.decrement();
                    e.complete("SUCCESS");
                    /**
                     * @TODO - Direct messages will only receive exception callback. Hence need to find a way
                     *       to identify error before calling success. Currently any exceptions when publishing direct messages
                     *       is ignored.
                     */
                }
            } catch (Exception exception) {
                isPublisherReady = false;
                e.fail(exception);
            } catch (Throwable t) {
                e.fail(t);
            }
        }).invoke(() -> SolaceLogging.log.successfullyToTopic(channel, topic.get().getName()));
    }

    public Flow.Subscriber<? extends Message<?>> getSubscriber() {
        return this.subscriber;
    }

    public void waitForPublishedMessages() {
        try {
            SolaceLogging.log.infof("Waiting for outgoing channel %s messages to be published", channel);
            if (!publishedMessagesTracker.awaitEmpty(this.gracefulShutdownWaitTimeout, TimeUnit.MILLISECONDS)) {
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
            waitForPublishedMessages();
        }
        if (processor != null) {
            processor.cancel();
        }

        publisher.close();
    }

    public void isStarted(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, !solace.isClosed());
    }

    public void isReady(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, !solace.isClosed() && this.publisher != null && !this.publisher.isClosed());
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
