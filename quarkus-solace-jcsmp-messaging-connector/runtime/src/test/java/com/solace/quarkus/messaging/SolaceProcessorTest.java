package com.solace.quarkus.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.Test;

import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.converters.SolaceMessageUtils;
import com.solacesystems.jcsmp.*;

import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class SolaceProcessorTest extends WeldTestBase {

    @Test
    void consumer() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic);

        // Run app that processes messages
        MyProcessor app = runApplication(config, MyProcessor.class);

        List<String> expected = new CopyOnWriteArrayList<>();

        try {
            // Start listening first
            EndpointProperties endpointProperties = new EndpointProperties();
            endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
            Queue queue = session.createTemporaryQueue();

            ConsumerFlowProperties consumerFlowProperties = new ConsumerFlowProperties();
            consumerFlowProperties.setEndpoint(queue);
            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage bytesXMLMessage) {
                    expected.add(SolaceMessageUtils.getPayloadAsString(bytesXMLMessage));
                }

                @Override
                public void onException(JCSMPException e) {

                }
            }, consumerFlowProperties, endpointProperties);
            session.addSubscription(queue, JCSMPFactory.onlyInstance().createTopic(processedTopic),
                    JCSMPSession.WAIT_FOR_CONFIRM);
            receiver.start();
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

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

        // Assert on received messages
        await().untilAsserted(() -> assertThat(app.getReceived()).contains("1", "2", "3", "4", "5"));
        // Assert on processed messages
        await().untilAsserted(() -> assertThat(expected).contains("1", "2", "3", "4", "5"));
    }

    @ApplicationScoped
    static class MyProcessor {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        @Outgoing("out")
        BytesXMLMessage in(BytesXMLMessage msg) {
            String payload = SolaceMessageUtils.getPayloadAsString(msg);
            received.add(payload);
            BytesXMLMessage bytesXMLMessage = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
            bytesXMLMessage.writeAttachment(payload.getBytes(StandardCharsets.UTF_8));
            return bytesXMLMessage;
        }

        public List<String> getReceived() {
            return received;
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
