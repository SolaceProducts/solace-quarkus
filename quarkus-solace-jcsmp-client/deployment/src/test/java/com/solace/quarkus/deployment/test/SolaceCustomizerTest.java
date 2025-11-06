package com.solace.quarkus.deployment.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.solace.quarkus.MessagingServiceClientCustomizer;
import com.solacesystems.jcsmp.*;

import io.quarkus.test.QuarkusUnitTest;

public class SolaceCustomizerTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(MyCustomizer.class));

    @Inject
    MyCustomizer customizer;
    @Inject
    JCSMPSession solace;

    @Test
    public void test() {
        try {
            XMLMessageProducer prod = solace.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object o) {

                }

                @Override
                public void handleErrorEx(Object o, JCSMPException e, long l) {

                }
            });
            assertThat(customizer.called()).isTrue();
            prod.close();
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        //        DirectMessagePublisher publisher = solace.createDirectMessagePublisherBuilder()
        //                .build().start();
    }

    @Singleton
    public static class MyCustomizer implements MessagingServiceClientCustomizer {

        AtomicBoolean called = new AtomicBoolean();

        @Override
        public JCSMPProperties customize(JCSMPProperties builder) {
            called.set(true);
            builder.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES_RECONNECT_RETRIES, 0);
            return builder;
        }

        public boolean called() {
            return called.get();
        }
    }
}
