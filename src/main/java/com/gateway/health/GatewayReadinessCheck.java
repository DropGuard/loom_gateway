package com.gateway.health;

import com.gateway.config.RouteConfigProvider;
import com.gateway.model.GatewayConfig;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Readiness
@Singleton
public class GatewayReadinessCheck implements HealthCheck {

    @Inject
    RouteConfigProvider routeConfigProvider;

    @Override
    public HealthCheckResponse call() {
        GatewayConfig config = routeConfigProvider.getConfig();
        boolean hasRoutes = config.routes() != null && !config.routes().isEmpty();
        return HealthCheckResponse.builder()
                .name("gateway-ready")
                .status(hasRoutes)
                .withData("routes", hasRoutes ? config.routes().size() : 0)
                .build();
    }
}
