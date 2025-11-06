package com.solace.quarkus.messaging;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.io.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.UUID;
import java.util.concurrent.*;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.testcontainers.utility.MountableFile;

import com.solace.quarkus.messaging.base.KeyCloakContainer;
import com.solace.quarkus.messaging.base.SolaceContainer;
import com.solace.quarkus.messaging.base.UnsatisfiedInstance;
import com.solace.quarkus.messaging.incoming.SolaceIncomingChannel;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Topic;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;
import io.vertx.mutiny.core.Vertx;

public class SolaceOAuthTest {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";

    private static SolaceContainer createSolaceContainer() {
        return new SolaceContainer(SOLACE_IMAGE);
    }

    private static KeyCloakContainer keyCloakContainer;
    private static SolaceContainer solaceContainer;

    @BeforeAll
    static void startContainers() {
        keyCloakContainer = new KeyCloakContainer();
        keyCloakContainer.start();
        keyCloakContainer.createHostsFile();
        await().until(() -> keyCloakContainer.isRunning());

        solaceContainer = createSolaceContainer();
        solaceContainer.withCredentials("user", "pass")
                .withClientCert(MountableFile.forClasspathResource("solace.pem"),
                        MountableFile.forClasspathResource("keycloak.crt"), false)
                .withOAuth()
                .withExposedPorts(SolaceContainer.Service.SMF.getPort(), SolaceContainer.Service.SMF_SSL.getPort(), 1943, 8080)
                .withPublishTopic("quarkus/integration/test/replay/messages", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/default/>", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/provisioned/>", SolaceContainer.Service.SMF)
                .withPublishTopic("quarkus/integration/test/dynamic/>", SolaceContainer.Service.SMF);

        solaceContainer.start();
        await().until(() -> solaceContainer.isRunning());
    }

    private static KeyStore createKeyStore(byte[] ca, byte[] serviceCa) {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            if (ca != null) {
                keyStore.setCertificateEntry("keycloak",
                        cf.generateCertificate(new ByteArrayInputStream(ca)));
            }
            if (serviceCa != null) {
                keyStore.setCertificateEntry("service-ca",
                        cf.generateCertificate(new ByteArrayInputStream(serviceCa)));
            }
            return keyStore;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getAccessToken() throws IOException {
        ClassLoader classLoader = SolaceOAuthTest.class.getClassLoader();
        InputStream is = new FileInputStream(classLoader.getResource("keycloak.crt").getFile());
        KeyStore trustStore = createKeyStore(is.readAllBytes(), null);
        Client resteasyClient = ClientBuilder.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .trustStore(trustStore)
                .hostnameVerifier(new DefaultHostnameVerifier())
                .build();

        Keycloak keycloak = KeycloakBuilder.builder()
                .serverUrl(keyCloakContainer.getOrigin(KeyCloakContainer.Service.HTTPS))
                .realm("solace")
                .clientId("solace")
                .clientSecret("solace-secret")
                .grantType("client_credentials")
                .resteasyClient(resteasyClient)
                .build();
        return keycloak.tokenManager().getAccessTokenString();
    }

    private JCSMPSession getMessagingService() throws IOException {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST,
                solaceContainer.getOrigin(SolaceContainer.Service.SMF_SSL));
        properties.setProperty(JCSMPProperties.VPN_NAME, solaceContainer.getVpn());
        properties.setProperty(JCSMPProperties.AUTHENTICATION_SCHEME, "AUTHENTICATION_SCHEME_OAUTH2");
        properties.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, false);
        properties.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE_HOST, false);
        properties.setProperty(JCSMPProperties.OAUTH2_ACCESS_TOKEN, getAccessToken());

        JCSMPSession session = null;
        try {
            session = JCSMPFactory.onlyInstance().createSession(properties);
            session.connect();
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }
        return session;
    }

    @Test
    void oauthTest() throws IOException {
        MapBasedConfig config = new MapBasedConfig()
                .with("channel-name", "in")
                .with("consumer.queue.name", "queue-" + UUID.randomUUID().getMostSignificantBits())
                .with("consumer.queue.add-additional-subscriptions", true)
                .with("consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("consumer.subscriptions", SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION);

        JCSMPSession session = getMessagingService();
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
            Topic tp = JCSMPFactory.onlyInstance().createTopic(SolaceContainer.INTEGRATION_TEST_QUEUE_SUBSCRIPTION);
            for (int i = 1; i <= 5; i++) {
                sendTextMessage(Integer.toString(i), publisher, tp);
            }
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        Awaitility.await().until(() -> list.size() == 5);
        // Assert on acknowledged messages
        solaceIncomingChannel.close();
        Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> ackedMessageList.size() == 5);
        executorService.shutdown();
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
