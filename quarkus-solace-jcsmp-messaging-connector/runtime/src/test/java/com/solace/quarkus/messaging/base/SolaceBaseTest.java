package com.solace.quarkus.messaging.base;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

import com.solacesystems.jcsmp.*;

@ExtendWith(SolaceBrokerExtension.class)
public class SolaceBaseTest {

    public static SolaceContainer solace;

    public static JCSMPSession session;

    public String topic;

    public String queue;

    @BeforeEach
    public void initTopic(TestInfo testInfo) {
        String cn = testInfo.getTestClass().map(Class::getSimpleName).orElse(UUID.randomUUID().toString());
        String mn = testInfo.getTestMethod().map(Method::getName).orElse(UUID.randomUUID().toString());
        topic = "quarkus/integration/test/default/" + cn + "/" + mn + "/" + UUID.randomUUID().getMostSignificantBits();
    }

    @BeforeEach
    public void initQueueName(TestInfo testInfo) {
        String cn = testInfo.getTestClass().map(Class::getSimpleName).orElse(UUID.randomUUID().toString());
        String mn = testInfo.getTestMethod().map(Method::getName).orElse(UUID.randomUUID().toString());
        queue = cn + "." + mn + "." + UUID.randomUUID().getMostSignificantBits();
    }

    @BeforeAll
    static void init(SolaceContainer container) {
        solace = container;
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
    }

}
