package com.github.dropguard.loom.health;

import com.github.dropguard.loom.config.RouteConfigProvider;
import com.github.dropguard.loom.model.GatewayConfig;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@Singleton
public class GatewayReadinessCheck implements HealthCheck {

    @Inject
    RouteConfigProvider routeConfigProvider;

    @Override
    public HealthCheckResponse call() {
        GatewayConfig config = routeConfigProvider.getConfig();
        boolean hasRoutes = config.routes() != null && !config.routes().isEmpty();
        // DEFECT #11: surface a distinct signal so alerting can tell
        // "loaded routes but unhealthy" apart from "config never loaded".
        boolean configMissing = config.routes() == null || config.routes().isEmpty();
        return HealthCheckResponse.builder()
                .name("gateway-ready")
                .status(hasRoutes)
                .withData("routes", hasRoutes ? config.routes().size() : 0)
                .withData("configMissing", configMissing)
                .build();
    }
}
