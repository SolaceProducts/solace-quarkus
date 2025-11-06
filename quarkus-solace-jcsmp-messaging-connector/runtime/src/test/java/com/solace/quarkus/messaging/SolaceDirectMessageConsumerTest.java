package com.solace.quarkus.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.*;
import java.util.concurrent.*;

import jakarta.enterprise.context.ApplicationScoped;

import org.awaitility.Durations;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.solace.quarkus.messaging.base.SolaceContainer;
import com.solace.quarkus.messaging.base.UnsatisfiedInstance;
import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.converters.SolaceMessageUtils;
import com.solace.quarkus.messaging.incoming.SolaceDirectMessageIncomingChannel;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;
import com.solace.quarkus.messaging.logging.SolaceTestAppender;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Topic;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;
import io.vertx.mutiny.core.Vertx;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SolaceDirectMessageConsumerTest extends WeldTestBase {
    private org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getLogger("com.solace.quarkus.messaging");
    private SolaceTestAppender solaceTestAppender = new SolaceTestAppender();

    private SolaceDirectMessageConsumerTest() {
        rootLogger.addAppender(solaceTestAppender);
    }

    @Test
    @Order(1)
    void consumer() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.client.type", "direct")
                .with("mp.messaging.incoming.in.consumer.subscriptions", "quarkus/integration/test/replay/messages");

        // Run app that consumes messages
        MyConsumer app = runApplication(config, MyConsumer.class);

        // Produce messages
        XMLMessageProducer publisher = null;
        try {
            publisher = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object o) {

                }

                @Override
                public void handleErrorEx(Object o, JCSMPException e, long l) {

                }
            });
            Topic tp = JCSMPFactory.onlyInstance().createTopic("quarkus/integration/test/replay/messages");
            for (int i = 1; i <= 5; i++) {
                sendTextMessage(Integer.toString(i), publisher, tp);
            }
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getReceived()).contains("1", "2", "3", "4", "5"));
    }

    @Test
    @Order(2)
    void consumerFailedProcessingPublishToErrorTopic() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.client.type", "direct")
                .with("mp.messaging.incoming.in.consumer.subscriptions",
                        SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION)
                .with("mp.messaging.incoming.in.consumer.failure-strategy", "error_topic")
                .with("mp.messaging.incoming.in.consumer.error.topic",
                        SolaceContainer.INTEGRATION_TEST_ERROR_QUEUE_SUBSCRIPTION)
                .with("mp.messaging.incoming.in.consumer.error.message.ttl", 1000)
                .with("mp.messaging.incoming.error-in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.error-in.consumer.queue.name", SolaceContainer.INTEGRATION_TEST_ERROR_QUEUE_NAME)
                .with("mp.messaging.incoming.error-in.consumer.queue.type", "durable-exclusive");

        // Run app that consumes messages
        MyErrorQueueConsumer app = runApplication(config, MyErrorQueueConsumer.class);

        // Produce messages
        XMLMessageProducer publisher = null;
        try {
            publisher = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object o) {

                }

                @Override
                public void handleErrorEx(Object o, JCSMPException e, long l) {

                }
            });
            Topic tp = JCSMPFactory.onlyInstance().createTopic(SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION);
            sendTextMessage("1", publisher, tp);
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getReceived().size()).isEqualTo(0));
        await().untilAsserted(() -> assertThat(app.getReceivedFailedMessages().size()).isEqualTo(1));
        await().untilAsserted(() -> assertThat(app.getReceivedFailedMessages()).contains("1"));
        await().pollDelay(Durations.FIVE_SECONDS).until(() -> true);
    }

    @Test
    @Order(3)
    void consumerPublishToErrorTopicPermissionException() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.client.type", "direct")
                .with("mp.messaging.incoming.in.consumer.subscriptions",
                        SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION)
                .with("mp.messaging.incoming.in.consumer.failure-strategy", "error_topic")
                .with("mp.messaging.incoming.in.consumer.error.topic",
                        "publish/deny")
                .with("mp.messaging.incoming.in.consumer.error.message.max-delivery-attempts", 0)
                .with("mp.messaging.incoming.error-in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.error-in.consumer.queue.name", SolaceContainer.INTEGRATION_TEST_ERROR_QUEUE_NAME)
                .with("mp.messaging.incoming.error-in.consumer.queue.type", "durable-exclusive");

        // Run app that consumes messages
        MyErrorQueueConsumer app = runApplication(config, MyErrorQueueConsumer.class);

        // Produce messages
        XMLMessageProducer publisher = null;
        try {
            publisher = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object o) {

                }

                @Override
                public void handleErrorEx(Object o, JCSMPException e, long l) {

                }
            });
            Topic tp = JCSMPFactory.onlyInstance().createTopic(SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION);
            sendTextMessage("2", publisher, tp);
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        await().untilAsserted(() -> assertThat(app.getReceivedFailedMessages().size()).isEqualTo(0));
        //        await().untilAsserted(() -> assertThat(inMemoryLogHandler.getRecords().stream().filter(record -> record.getMessage().contains("A exception occurred when publishing to topic")).count()).isEqualTo(4));
        await().untilAsserted(() -> assertThat(solaceTestAppender.getLog().stream()
                .anyMatch(record -> record.getMessage().toString().contains("Publishing error message to topic")))
                .isEqualTo(true));
    }

    @Test
    @Order(4)
    void consumerGracefulCloseTest() {
        MapBasedConfig config = new MapBasedConfig()
                .with("channel-name", "in")
                .with("client.type", "direct")
                .with("consumer.subscriptions", topic);

        // Initialize incoming channel to consumes messages
        SolaceDirectMessageIncomingChannel solaceIncomingChannel = new SolaceDirectMessageIncomingChannel(Vertx.vertx(),
                UnsatisfiedInstance.instance(), new SolaceConnectorIncomingConfiguration(config), session);

        CopyOnWriteArrayList<Object> list = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Object> ackedMessageList = new CopyOnWriteArrayList<>();

        Flow.Publisher<? extends Message<?>> stream = solaceIncomingChannel.getStream();
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        Multi.createFrom().publisher(stream).subscribe().with(message -> {
            list.add(message);
            executorService.schedule(() -> {
                ackedMessageList.add(message);
                CompletableFuture.runAsync(message::ack);
            }, 1, TimeUnit.SECONDS);
        });

        // Produce messages
        XMLMessageProducer publisher = null;
        try {
            publisher = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object o) {

                }

                @Override
                public void handleErrorEx(Object o, JCSMPException e, long l) {

                }
            });
            Topic tp = JCSMPFactory.onlyInstance().createTopic(topic);
            for (int i = 1; i <= 5; i++) {
                sendTextMessage(Integer.toString(i), publisher, tp);
            }
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        await().until(() -> list.size() == 5);
        // Assert on acknowledged messages
        solaceIncomingChannel.close();
        await().atMost(2, TimeUnit.MINUTES).until(() -> ackedMessageList.size() == 5);
        executorService.shutdown();
    }

    @ApplicationScoped
    static class MyConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        CompletionStage<Void> in(SolaceInboundMessage<byte[]> msg) {
            received.add(SolaceMessageUtils.getPayloadAsString(msg.getMessage()));
            return msg.ack();
        }

        public List<String> getReceived() {
            return received;
        }
    }

    @ApplicationScoped
    static class MyDMQConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();

        private List<String> receivedDMQMessages = new CopyOnWriteArrayList<>();

        @Incoming("in")
        void in(String msg) {
            received.add(msg);
        }

        @Incoming("dmq-in")
        void dmqin(BytesXMLMessage msg) {
            receivedDMQMessages.add(SolaceMessageUtils.getPayloadAsString(msg));
        }

        public List<String> getReceived() {
            return received;
        }

        public List<String> getReceivedDMQMessages() {
            return receivedDMQMessages;
        }
    }

    @ApplicationScoped
    static class MyErrorQueueConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();
        private List<String> receivedFailedMessages = new CopyOnWriteArrayList<>();

        @Incoming("in")
        void in(String msg) {
            received.add(msg);
        }

        @Incoming("error-in")
        void errorin(BytesXMLMessage msg) {
            receivedFailedMessages.add(SolaceMessageUtils.getPayloadAsString(msg));
        }

        public List<String> getReceived() {
            return received;
        }

        public List<String> getReceivedFailedMessages() {
            return receivedFailedMessages;
        }
    }

    @ApplicationScoped
    static class MyPartitionedQueueConsumer {
        Map<String, Integer> partitionMessages = new HashMap<>() {
            {
                put("Group-1", 0);
                put("Group-2", 0);
                put("Group-3", 0);
                put("Group-4", 0);
            }
        };

        @Incoming("consumer-1")
        CompletionStage<Void> consumer1(SolaceInboundMessage<?> msg) {
            updatePartitionMessages(msg);
            return msg.ack();
        }

        @Incoming("consumer-2")
        CompletionStage<Void> consumer2(SolaceInboundMessage<?> msg) {
            updatePartitionMessages(msg);
            return msg.ack();
        }

        @Incoming("consumer-3")
        CompletionStage<Void> consumer3(SolaceInboundMessage<?> msg) {
            updatePartitionMessages(msg);
            return msg.ack();
        }

        @Incoming("consumer-4")
        CompletionStage<Void> consumer4(SolaceInboundMessage<?> msg) {
            updatePartitionMessages(msg);
            return msg.ack();
        }

        private void updatePartitionMessages(SolaceInboundMessage<?> msg) {
            String partitionKey = null;
            try {
                partitionKey = msg.getMessage().getProperties()
                        .getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY);
                int count = partitionMessages.get(partitionKey);
                partitionMessages.put(partitionKey, (count + 1));
            } catch (SDTException e) {
                throw new RuntimeException(e);
            }

        }

        public Map<String, Integer> getPartitionMessages() {
            return partitionMessages;
        }
    }

    private void sendTextMessage(String payload, XMLMessageProducer publisher, Topic tp) {
        TextMessage textMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        textMessage.setText(payload);
        textMessage.setDeliveryMode(DeliveryMode.PERSISTENT);
        try {
            publisher.send(textMessage, tp);
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }
    }
}
