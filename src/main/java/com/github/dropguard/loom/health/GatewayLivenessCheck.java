package com.github.dropguard.loom.health;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@Singleton
public class GatewayLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.builder().name("gateway-live").up().build();
    }
}
