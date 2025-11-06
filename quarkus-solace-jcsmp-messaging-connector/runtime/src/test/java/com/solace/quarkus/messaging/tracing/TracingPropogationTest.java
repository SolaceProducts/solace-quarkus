package com.solace.quarkus.messaging.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.solace.quarkus.messaging.base.MessagingServiceProvider;
import com.solace.quarkus.messaging.base.SolaceContainer;
import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.converters.SolaceMessageUtils;
import com.solace.quarkus.messaging.incoming.SolaceInboundMetadata;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.Topic;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

@Execution(ExecutionMode.SAME_THREAD)
public class TracingPropogationTest extends WeldTestBase {
    private SdkTracerProvider tracerProvider;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    public void setup() {
        GlobalOpenTelemetry.resetForTest();

        spanExporter = InMemorySpanExporter.create();
        SpanProcessor spanProcessor = SimpleSpanProcessor.create(spanExporter);

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanProcessor)
                .setSampler(Sampler.alwaysOn())
                .build();

        OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }

    @AfterAll
    static void shutdown() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    @Order(2)
    void consumer() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.client.tracing-enabled", "true")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
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

        CompletableResultCode completableResultCode = tracerProvider.forceFlush();
        completableResultCode.whenComplete(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(5, spans.size());

            assertEquals(5, spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet()).size());

            SpanData span = spans.get(0);
            assertEquals(SpanKind.CONSUMER, span.getKind());
        });
    }

    @Test
    void publisher() {
        session.closeSession();
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, solace.getOrigin(SolaceContainer.Service.SMF));
        properties.setProperty(JCSMPProperties.VPN_NAME, solace.getVpn());
        properties.setProperty(JCSMPProperties.USERNAME, solace.getUsername());
        properties.setProperty(JCSMPProperties.PASSWORD, solace.getPassword());

        try {
            session = JCSMPFactory.onlyInstance().createSession(properties);
            session.connect();
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        MessagingServiceProvider.messagingService = session;
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.outgoing.out.client.tracing-enabled", "true")
                .with("mp.messaging.outgoing.out.producer.topic", topic);

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        try {
            EndpointProperties endpointProperties = new EndpointProperties();
            endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
            Queue queue = session.createTemporaryQueue();
            // Start listening first
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
            session.addSubscription(queue, JCSMPFactory.onlyInstance().createTopic(topic), JCSMPSession.WAIT_FOR_CONFIRM);
            receiver.start();
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);
        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getAcked()).contains("1", "2", "3", "4", "5"));
        // Assert on received messages
        await().untilAsserted(() -> assertThat(expected).contains("1", "2", "3", "4", "5"));

        CompletableResultCode completableResultCode = tracerProvider.forceFlush();
        completableResultCode.whenComplete(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(5, spans.size());

            assertEquals(5, spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet()).size());

            SpanData span = spans.get(0);
            assertEquals(SpanKind.PRODUCER, span.getKind());
        });
    }

    @Test
    @Order(1)
    void processor() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.incoming.in.client.tracing-enabled", "true")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace-jcsmp")
                .with("mp.messaging.outgoing.out.client.tracing-enabled", "true")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic);

        // Run app that processes messages
        MyProcessor app = runApplication(config, MyProcessor.class);

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        try {
            EndpointProperties endpointProperties = new EndpointProperties();
            endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
            Queue queue = session.createTemporaryQueue();
            // Start listening first
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

        CompletableResultCode completableResultCode = tracerProvider.forceFlush();
        completableResultCode.whenComplete(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(10, spans.size());

            assertEquals(5, spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet()).size());

            SpanData span = spans.get(0);
            assertEquals(SpanKind.CONSUMER, span.getKind());

            span = spans.get(5);
            assertEquals(SpanKind.PRODUCER, span.getKind());
        });
    }

    @ApplicationScoped
    static class MyConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        CompletionStage<Void> in(Message<byte[]> msg) {
            SolaceInboundMetadata solaceInboundMetadata = msg.getMetadata(SolaceInboundMetadata.class).orElseThrow();
            received.add(solaceInboundMetadata.getPayloadAsString());
            return msg.ack();
        }

        public List<String> getReceived() {
            return received;
        }
    }

    @ApplicationScoped
    static class MyApp {
        private final List<String> acked = new CopyOnWriteArrayList<>();

        @Outgoing("out")
        Multi<Message<String>> out() {

            return Multi.createFrom().items("1", "2", "3", "4", "5")
                    .map(payload -> Message.of(payload).withAck(() -> {
                        acked.add(payload);
                        return CompletableFuture.completedFuture(null);
                    }));
        }

        public List<String> getAcked() {
            return acked;
        }
    }

    @ApplicationScoped
    static class MyProcessor {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        @Outgoing("out")
        BytesXMLMessage in(BytesXMLMessage msg) {
            String payload = SolaceMessageUtils.getPayloadAsString(msg);
            received.add(payload);
            BytesXMLMessage bytesXMLMessage = JCSMPFactory.onlyInstance().createBytesXMLMessage();
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
        //        textMessage.setHTTPContentType(HttpHeaderValues.TEXT_PLAIN.toString());
        textMessage.setDeliveryMode(DeliveryMode.PERSISTENT);
        try {
            publisher.send(textMessage, tp);
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }
    }
}
