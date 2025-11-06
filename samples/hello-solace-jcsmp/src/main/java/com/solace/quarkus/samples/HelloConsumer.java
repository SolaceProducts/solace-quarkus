package com.solace.quarkus.samples;

import java.nio.charset.StandardCharsets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.solacesystems.jcsmp.*;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class HelloConsumer {

    @Inject
    JCSMPSession solace;

    private XMLMessageConsumer receiver;

    public void init(@Observes StartupEvent ev) throws JCSMPException {
        receiver = solace.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage bytesXMLMessage) {
                Log.infof("Received message: %s", getPayloadAsString(bytesXMLMessage));
            }

            @Override
            public void onException(JCSMPException e) {

            }
        });
        solace.addSubscription(JCSMPFactory.onlyInstance().createTopic("hello/foobar"));
        receiver.start();
    }

    public void stop(@Observes ShutdownEvent ev) {
        receiver.stop();
    }

    public String getPayloadAsString(BytesXMLMessage solaceMessage) {
        if (solaceMessage.hasContent() && solaceMessage.hasAttachment()) {
            throw new RuntimeException("multiple payloads");
        } else if (!solaceMessage.hasAttachment()) {
            byte[] bytesPayload = solaceMessage.getBytes();
            return bytesPayload == null ? "" : new String(bytesPayload, StandardCharsets.UTF_8);
        } else if (solaceMessage instanceof TextMessage) {
            return ((TextMessage) solaceMessage).getText();
        } else if (solaceMessage instanceof XMLContentMessage) {
            return ((XMLContentMessage) solaceMessage).getAttachmentByteBuffer() == null ? ""
                    : new String(((XMLContentMessage) solaceMessage).getAttachmentByteBuffer().array(), StandardCharsets.UTF_8);
        } else if (solaceMessage instanceof BytesMessage) {
            BytesMessage bytesMessage = (BytesMessage) solaceMessage;
            byte[] bytesPayload = bytesMessage.getData();
            return bytesPayload == null ? "" : new String(bytesPayload, StandardCharsets.UTF_8);
        } else if (!(solaceMessage instanceof MapMessage)) {
            return solaceMessage.getAttachmentByteBuffer() == null ? ""
                    : new String(solaceMessage.getAttachmentByteBuffer().array(), StandardCharsets.UTF_8);
        } else {
            MapMessage mapMessage = (MapMessage) solaceMessage;
            SDTMap mapPayload = mapMessage.getMap();
            if (mapPayload == null) {
                return "";
            } else {
                try {
                    return mapPayload.toString();
                } catch (Exception e) {
                    //                    if (logger.isWarnEnabled()) {
                    //                        logger.warn("SDT message PayloadAsString conversion failed.", e);
                    //                    }

                    return "";
                }
            }
        }
    }
}
