package com.solace.quarkus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import com.solacesystems.jcsmp.*;

import io.quarkus.runtime.ShutdownEvent;

@ApplicationScoped
public class SolaceConsumer {

    private final XMLMessageConsumer directReceiver;
    private final FlowReceiver persistentReceiver;
    List<String> direct = new CopyOnWriteArrayList<>();
    List<String> persistent = new CopyOnWriteArrayList<>();

    public SolaceConsumer(JCSMPSession solace) throws JCSMPException {
        directReceiver = solace.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage bytesXMLMessage) {
                if (bytesXMLMessage instanceof TextMessage) {
                    consumeDirect(((TextMessage) bytesXMLMessage).getText());
                }
            }

            @Override
            public void onException(JCSMPException e) {

            }
        });
        solace.addSubscription(JCSMPFactory.onlyInstance().createTopic("hello/direct"));
        directReceiver.start();

        // configure the queue API object locally
        final Queue queue = JCSMPFactory.onlyInstance().createQueue("persistent");
        // Create a Flow be able to bind to and consume messages from the Queue.
        final ConsumerFlowProperties flow_prop = new ConsumerFlowProperties();
        flow_prop.setEndpoint(queue);

        EndpointProperties endpoint_props = new EndpointProperties();
        endpoint_props.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
        endpoint_props.setPermission(EndpointProperties.PERMISSION_CONSUME);
        endpoint_props.setQuota(5);
        solace.provision(queue, endpoint_props, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
        try {
            solace.addSubscription(queue, JCSMPFactory.onlyInstance().createTopic("hello/persistent"),
                    JCSMPSession.WAIT_FOR_CONFIRM);
        } catch (JCSMPException e) {
        }

        persistentReceiver = solace.createFlow(new XMLMessageListener() {

            @Override
            public void onReceive(BytesXMLMessage bytesXMLMessage) {
                if (bytesXMLMessage instanceof TextMessage) {
                    consumePersistent(((TextMessage) bytesXMLMessage).getText());
                }
            }

            @Override
            public void onException(JCSMPException e) {

            }
        }, flow_prop, endpoint_props);
        persistentReceiver.start();
    }

    public void shutdown(@Observes ShutdownEvent event) {
        directReceiver.close();
        persistentReceiver.close();
    }

    public void consumeDirect(String message) {
        direct.add(message);
    }

    public void consumePersistent(String message) {
        persistent.add(message);
    }

    public List<String> direct() {
        return direct;
    }

    public List<String> persistent() {
        return persistent;
    }
}
