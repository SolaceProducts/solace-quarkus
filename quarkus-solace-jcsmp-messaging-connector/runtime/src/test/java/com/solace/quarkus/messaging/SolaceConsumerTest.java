package com.solace.quarkus.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;
import com.solace.quarkus.messaging.incoming.SolaceIncomingChannel;
import com.solace.quarkus.messaging.logging.SolaceTestAppender;
import com.solacesystems.jcsmp.*;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;
import io.vertx.mutiny.core.Vertx;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SolaceConsumerTest extends WeldTestBase {
    private org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getLogger("com.solace.quarkus.messaging");
    private SolaceTestAppender solaceTestAppender = new SolaceTestAppender();

    private SolaceConsumerTest() {
        rootLogger.addAppender(solaceTestAppender);
    }

    @Test
    @Order(1)
    void consumer() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", "quarkus/integration/test/replay/messages");

        // Run app that consumes messages
        MyConsumer app = runApplication(config, MyConsumer.class);

        // Produce messages
        XMLMessageProducer publisher;
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
                sendTextMessage(Integer.toString(i), publisher, tp, -1, false, new HashMap<>());
            }
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getReceived()).contains("1", "2", "3", "4", "5"));
    }

    @Test
    @Order(2)
    void consumerReplay() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.type", "durable-exclusive")
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", "quarkus/integration/test/replay/messages")
                .with("mp.messaging.incoming.in.consumer.queue.replay.strategy", "all-messages");

        // Run app that consumes messages
        MyConsumer app = runApplication(config, MyConsumer.class);

        // Assert on published messages
        //        await().untilAsserted(() -> assertThat(app.getReceived().size()).isEqualTo(5));
        await().untilAsserted(() -> assertThat(app.getReceived()).contains("1", "2", "3", "4", "5"));
    }

    @Test
    @Order(3)
    void consumerWithSelectorQuery() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.queue.selector-query", "id = '1'")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic);

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
            Topic tp = JCSMPFactory.onlyInstance().createTopic(topic);
            for (int i = 1; i <= 5; i++) {
                Map<String, String> props = new HashMap<>();
                props.put("id", Integer.toString(i));
                sendTextMessage(Integer.toString(i), publisher, tp, -1, false, props);
            }
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getReceived().size()).isEqualTo(1));
        await().untilAsserted(() -> assertThat(app.getReceived()).contains("1"));
    }

    @Test
    @Order(4)
    void consumerFailedProcessingPublishToErrorTopic() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.name", SolaceContainer.INTEGRATION_TEST_QUEUE_NAME)
                .with("mp.messaging.incoming.in.consumer.queue.type", "durable-exclusive")
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
            sendTextMessage("1", publisher, tp, -1, false, new HashMap<>());
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
    @Order(5)
    void consumerFailedProcessingMoveToDMQ() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.name", SolaceContainer.INTEGRATION_TEST_QUEUE_NAME)
                .with("mp.messaging.incoming.in.consumer.queue.type", "durable-exclusive")
                .with("mp.messaging.incoming.in.consumer.queue.supports-nacks", "true")
                .with("mp.messaging.incoming.in.consumer.failure-strategy", "discard")
                .with("mp.messaging.incoming.dmq-in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.dmq-in.consumer.queue.name", SolaceContainer.INTEGRATION_TEST_DMQ_NAME)
                .with("mp.messaging.incoming.dmq-in.consumer.queue.type", "durable-exclusive");

        // Run app that consumes messages
        MyDMQConsumer app = runApplication(config, MyDMQConsumer.class);

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
            sendTextMessage("12", publisher, tp, 0, true, new HashMap<>());
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getReceived().size()).isEqualTo(0));
        await().untilAsserted(() -> assertThat(app.getReceivedDMQMessages().size()).isEqualTo(1));
        await().untilAsserted(() -> assertThat(app.getReceivedDMQMessages()).contains("12"));
    }

    @Test
    @Order(6)
    void partitionedQueue() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.consumer-1.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.consumer-1.consumer.queue.name",
                        SolaceContainer.INTEGRATION_TEST_PARTITION_QUEUE_NAME)
                .with("mp.messaging.incoming.consumer-1.consumer.queue.type", "durable-non-exclusive")
                .with("mp.messaging.incoming.consumer-2.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.consumer-2.consumer.queue.name",
                        SolaceContainer.INTEGRATION_TEST_PARTITION_QUEUE_NAME)
                .with("mp.messaging.incoming.consumer-2.consumer.queue.type", "durable-non-exclusive")
                .with("mp.messaging.incoming.consumer-3.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.consumer-3.consumer.queue.name",
                        SolaceContainer.INTEGRATION_TEST_PARTITION_QUEUE_NAME)
                .with("mp.messaging.incoming.consumer-3.consumer.queue.type", "durable-non-exclusive")
                .with("mp.messaging.incoming.consumer-4.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.consumer-4.consumer.queue.name",
                        SolaceContainer.INTEGRATION_TEST_PARTITION_QUEUE_NAME)
                .with("mp.messaging.incoming.consumer-4.consumer.queue.type", "durable-non-exclusive");

        // Run app that consumes messages
        MyPartitionedQueueConsumer app = runApplication(config, MyPartitionedQueueConsumer.class);

        CopyOnWriteArrayList<String> partitionKeys = new CopyOnWriteArrayList<>() {
            {
                add("Group-1");
                add("Group-2");
                add("Group-3");
                add("Group-4");
            }
        };
        Map<String, Integer> partitionMessages = new HashMap<>() {
            {
                put(partitionKeys.get(0), 0);
                put(partitionKeys.get(1), 0);
                put(partitionKeys.get(2), 0);
                put(partitionKeys.get(3), 0);
            }
        };

        Random random = new Random();
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
            Topic tp = JCSMPFactory.onlyInstance().createTopic(SolaceContainer.INTEGRATION_TEST_PARTITION_QUEUE_SUBSCRIPTION);

            for (int i = 0; i < 1000; i++) {
                int partitionIndex = random.nextInt(4);
                String partitionKey = partitionKeys.get(partitionIndex);
                int count = partitionMessages.get(partitionKey);
                partitionMessages.put(partitionKey, (count + 1));

                Map<String, String> properties = new HashMap<>();
                properties.put(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, partitionKey);
                sendTextMessage("12", publisher, tp, -1, false, properties);
            }
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        // Assert on published and consumed messages
        await().untilAsserted(() -> assertThat(app.getPartitionMessages().get(partitionKeys.get(0)))
                .isEqualTo(partitionMessages.get(partitionKeys.get(0))));
        await().untilAsserted(() -> assertThat(app.getPartitionMessages().get(partitionKeys.get(1)))
                .isEqualTo(partitionMessages.get(partitionKeys.get(1))));
        await().untilAsserted(() -> assertThat(app.getPartitionMessages().get(partitionKeys.get(2)))
                .isEqualTo(partitionMessages.get(partitionKeys.get(2))));
        await().untilAsserted(() -> assertThat(app.getPartitionMessages().get(partitionKeys.get(3)))
                .isEqualTo(partitionMessages.get(partitionKeys.get(3))));
    }

    @Test
    @Order(7)
    void consumerPublishToErrorTopicPermissionException() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.name", SolaceContainer.INTEGRATION_TEST_QUEUE_NAME)
                .with("mp.messaging.incoming.in.consumer.queue.type", "durable-exclusive")
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
        XMLMessageProducer publisher;
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
            sendTextMessage("2", publisher, tp, -1, false, new HashMap<>());
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
    @Order(8)
    void consumerGracefulCloseTest() {
        MapBasedConfig config = new MapBasedConfig()
                .with("channel-name", "in")
                .with("consumer.queue.name", queue)
                .with("consumer.queue.add-additional-subscriptions", true)
                .with("consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("consumer.subscriptions", topic);

        // Initialize incoming channel to consumes messages
        SolaceIncomingChannel solaceIncomingChannel = new SolaceIncomingChannel(Vertx.vertx(), UnsatisfiedInstance.instance(),
                new SolaceConnectorIncomingConfiguration(config), session);

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
                sendTextMessage(Integer.toString(i), publisher, tp, -1, false, new HashMap<>());
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

    @Test
    @Order(9)
    void consumerCreateMissingResourceAddSubscriptionPermissionException() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.queue.name", SolaceContainer.INTEGRATION_TEST_QUEUE_NAME)
                .with("mp.messaging.incoming.in.consumer.queue.type", "durable-exclusive")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic);

        Exception exception = assertThrows(Exception.class, () -> {
            // Run app that consumes messages
            MyConsumer app = runApplication(config, MyConsumer.class);
        });

        // Assert on published messages
        await().untilAsserted(() -> assertThat(exception.getMessage())
                .contains("com.solacesystems.jcsmp.AccessDeniedException: Permission Not Allowed - Queue '"
                        + SolaceContainer.INTEGRATION_TEST_QUEUE_NAME + "' - Topic '" + topic));
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

    private void sendTextMessage(String payload, XMLMessageProducer publisher, Topic tp, int ttl, boolean dmqEligible,
            Map<String, String> properties) {
        TextMessage textMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        textMessage.setText(payload);
        textMessage.setDeliveryMode(DeliveryMode.PERSISTENT);
        if (ttl > -1) {
            textMessage.setTimeToLive(ttl);
        }
        textMessage.setDMQEligible(dmqEligible);
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            try {
                sdtMap.putString(entry.getKey(), entry.getValue());
            } catch (SDTException e) {
                throw new RuntimeException(e);
            }
        }
        textMessage.setProperties(sdtMap);
        try {
            publisher.send(textMessage, tp);
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }
    }
}
