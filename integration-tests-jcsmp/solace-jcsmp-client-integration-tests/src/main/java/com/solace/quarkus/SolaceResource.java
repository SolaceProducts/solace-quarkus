package com.solace.quarkus;

import java.util.List;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import com.solacesystems.jcsmp.*;

import io.quarkus.runtime.StartupEvent;

@Path("/solace")
public class SolaceResource {

    @Inject
    SolaceConsumer consumer;

    @Inject
    JCSMPSession solace;
    private XMLMessageProducer directMessagePublisher;
    private XMLMessageProducer persistentMessagePublisher;

    public void init(@Observes StartupEvent ev) {
        try {
            directMessagePublisher = solace.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object o) {

                }

                @Override
                public void handleErrorEx(Object o, JCSMPException e, long l) {

                }
            });
            persistentMessagePublisher = solace.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
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

    @GET
    @Path("/direct")
    @Produces("application/json")
    public List<String> getDirectMessages() {
        return consumer.direct();
    }

    @GET
    @Path("/persistent")
    @Produces("application/json")
    public List<String> getPersistentMessages() {
        return consumer.persistent();
    }

    @POST
    @Path("/direct")
    public void sendDirect(String message) {
        TextMessage textMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        textMessage.setText(message);
        textMessage.setDeliveryMode(DeliveryMode.DIRECT);
        try {
            directMessagePublisher.send(textMessage, JCSMPFactory.onlyInstance().createTopic("hello/direct"));
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }
    }

    @POST
    @Path("/persistent")
    public void sendPersistent(String message) {
        TextMessage textMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        textMessage.setText(message);
        textMessage.setDeliveryMode(DeliveryMode.PERSISTENT);
        try {
            persistentMessagePublisher.send(textMessage, JCSMPFactory.onlyInstance().createTopic("hello/persistent"));
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }
    }

}
