package com.solace.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

import com.solacesystems.jcsmp.JCSMPProperties;

@ApplicationScoped
public class SolaceCustomizer implements MessagingServiceClientCustomizer {
    @Override
    public JCSMPProperties customize(JCSMPProperties builder) {
        return builder;
    }
}
