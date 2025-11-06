package com.solace.quarkus.samples;

import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import com.solacesystems.jcsmp.*;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;

@Path("/hello")
public class PublisherResource {

    private final XMLMessageProducer publisher;

    public PublisherResource(JCSMPSession solace) throws JCSMPException {
        publisher = solace.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object o) {
                Log.infof("Message Published Successfully: %s", o);
            }

            @Override
            public void handleErrorEx(Object o, JCSMPException e, long l) {

            }
        });
    }

    @POST
    public void publish(String message) throws JCSMPException {
        String topicString = "hello/foobar";
        TextMessage textMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        textMessage.setText(message);
        textMessage.setDeliveryMode(DeliveryMode.PERSISTENT);
        textMessage.setCorrelationId(message);
        textMessage.setCorrelationKey(message);
        publisher.send(textMessage, JCSMPFactory.onlyInstance().createTopic(topicString));
    }

    public void stop(@Observes ShutdownEvent ev) {
        publisher.close();
    }
}
