package com.solace.quarkus.messaging.base;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.solacesystems.jcsmp.JCSMPSession;

@ApplicationScoped
public class MessagingServiceProvider {

    public static JCSMPSession messagingService;

    @Produces
    JCSMPSession messagingService() {
        return messagingService;
    }
}
