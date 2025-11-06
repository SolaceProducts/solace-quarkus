package com.solace.quarkus.runtime.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import com.solacesystems.jcsmp.JCSMPSession;

@Liveness
@ApplicationScoped
public class SolaceHealthCheck implements HealthCheck {

    @Inject
    JCSMPSession solace;

    @Override
    public HealthCheckResponse call() {
        if (!solace.isClosed()) {
            return HealthCheckResponse.down("solace");
        }
        return HealthCheckResponse.up("solace");
    }
}
