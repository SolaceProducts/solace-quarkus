package com.solace.quarkus.messaging;

import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;

import io.smallrye.mutiny.subscription.UniEmitter;

public class PublishReceipt implements JCSMPStreamingPublishCorrelatingEventHandler {
    @Override
    public void responseReceivedEx(Object o) {
        if (o instanceof UniEmitter) {
            ((UniEmitter<Object>) o).complete("SUCCESS");
        }
    }

    @Override
    public void handleErrorEx(Object o, JCSMPException e, long l) {
        if (o instanceof UniEmitter) {
            ((UniEmitter<Object>) o).fail(e);
        }
    }
}
