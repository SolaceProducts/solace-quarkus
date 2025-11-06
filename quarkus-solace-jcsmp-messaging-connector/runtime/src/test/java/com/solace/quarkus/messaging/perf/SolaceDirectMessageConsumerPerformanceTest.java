package com.solace.quarkus.messaging.perf;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.junit.jupiter.api.Test;

import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Topic;

import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class SolaceDirectMessageConsumerPerformanceTest extends WeldTestBase {
    private static final int COUNT = 100000;
    private static final int TIMEOUT_IN_SECONDS = 400;

    @Test
    public void solaceConsumerPerformanceTest() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.client.type", "direct")
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic);

        // Run app that consumes messages
        MyConsumer app = runApplication(config, MyConsumer.class);

        await().until(() -> isStarted() && isReady());

        // Produce messages
        XMLMessageProducer publisher = null;
        Topic tp = JCSMPFactory.onlyInstance().createTopic(topic);
        try {
            publisher = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object o) {

                }

                @Override
                public void handleErrorEx(Object o, JCSMPException e, long l) {

                }
            });

            for (int i = 0; i < COUNT; i++) {
                sendTextMessage(Integer.toString(i + 1), publisher, tp);
            }
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> app.getCount() == COUNT);
        long start = app.getStart();
        long end = System.currentTimeMillis();

        System.out.println("Total time : " + (end - start) + " ms");

    }

    @ApplicationScoped
    static class MyConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();
        LongAdder count = new LongAdder();
        long start;

        @Incoming("in")
        public CompletionStage<Void> in(SolaceInboundMessage<byte[]> msg) {
            if (count.longValue() == 0L) {
                start = System.currentTimeMillis();
            }
            count.increment();
            return msg.ack();
        }

        public List<String> getReceived() {
            return received;
        }

        public long getStart() {
            return start;
        }

        public long getCount() {
            return count.longValue();
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
