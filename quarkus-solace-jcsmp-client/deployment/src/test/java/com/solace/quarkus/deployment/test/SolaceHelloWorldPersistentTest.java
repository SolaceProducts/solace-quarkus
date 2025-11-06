package com.solace.quarkus.deployment.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.solacesystems.jcsmp.*;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.QuarkusTestResource;

/**
 * Based on
 * <a href=
 * "https://github.com/SolaceSamples/solace-samples-java/blob/main/src/main/java/com/solace/samples/java/HelloWorld.java">Hello
 * World</a>
 * but use persistent messaging.
 */
@QuarkusTestResource(SolaceTestResource.class)
public class SolaceHelloWorldPersistentTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(HelloWorldReceiver.class, HelloWorldPublisher.class));

    @Inject
    HelloWorldPublisher publisher;
    @Inject
    HelloWorldReceiver receiver;

    @Inject
    JCSMPSession session;

    @Test
    public void hello() {
        publisher.send("Hello World 1");
        publisher.send("Hello World 2");
        publisher.send("Hello World 3");

        await().until(() -> receiver.list().size() == 3);

        for (BytesXMLMessage message : receiver.list()) {
            assertThat(new String(message.getAttachmentByteBuffer().array(), StandardCharsets.UTF_8)).startsWith("Hello World");
        }
    }

    @ApplicationScoped
    public static class HelloWorldReceiver {

        @Inject
        JCSMPSession session;
        private FlowReceiver receiver;
        private final List<BytesXMLMessage> list = new CopyOnWriteArrayList<>();

        public void init(@Observes StartupEvent ev) {
            try {

                EndpointProperties endpointProperties = new EndpointProperties();
                endpointProperties.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
                Queue queue = JCSMPFactory.onlyInstance().createQueue("my-queue");

                ConsumerFlowProperties consumerFlowProperties = new ConsumerFlowProperties();
                consumerFlowProperties.setEndpoint(queue);

                session.provision(queue, endpointProperties, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
                receiver = session.createFlow(new XMLMessageListener() {
                    @Override
                    public void onReceive(BytesXMLMessage bytesXMLMessage) {
                        list.add(bytesXMLMessage);
                        bytesXMLMessage.ackMessage();
                    }

                    @Override
                    public void onException(JCSMPException e) {

                    }
                }, consumerFlowProperties, endpointProperties);
                session.addSubscription(queue, JCSMPFactory.onlyInstance().createTopic("hello/persistent"),
                        JCSMPSession.WAIT_FOR_CONFIRM);
                receiver.start();
            } catch (JCSMPException e) {
                throw new RuntimeException(e);
            }
        }

        public List<BytesXMLMessage> list() {
            return list;
        }

        public void stop(@Observes ShutdownEvent ev) {
            receiver.close();
        }
    }

    @ApplicationScoped
    public static class HelloWorldPublisher {
        @Inject
        JCSMPSession session;
        private XMLMessageProducer publisher;

        public void init(@Observes StartupEvent ev) {
            try {
                publisher = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                    @Override
                    public void responseReceivedEx(Object o) {

                    }

                    @Override
                    public void handleErrorEx(Object o, JCSMPException e, long l) {

                    }
                });
            } catch (JCSMPException e) {
                throw new RuntimeException(e);
            }
        }

        public void send(String message) {
            String topicString = "hello/persistent";
            BytesXMLMessage bytesXMLMessage = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
            bytesXMLMessage.writeAttachment(message.getBytes(StandardCharsets.UTF_8));

            try {
                publisher.send(bytesXMLMessage, JCSMPFactory.onlyInstance().createTopic(topicString));
            } catch (JCSMPException e) {
                throw new RuntimeException(e);
            }
        }

        public void stop(@Observes ShutdownEvent ev) {
            publisher.close();
        }
    }
}
