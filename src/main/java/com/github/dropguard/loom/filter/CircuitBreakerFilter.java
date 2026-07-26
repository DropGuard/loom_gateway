package com.github.dropguard.loom.filter;

import com.github.dropguard.loom.metrics.GatewayMetrics;
import com.github.dropguard.loom.model.RouteConfig.CircuitBreakerConfig;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

@Singleton
public class CircuitBreakerFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(CircuitBreakerFilter.class);
    private final CircuitBreakerRegistry registry;
    private final GatewayMetrics metrics;

    @Inject
    public CircuitBreakerFilter(CircuitBreakerRegistry registry, GatewayMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @Override
    public void apply(FilterContext context, Next next) throws Exception {
        CircuitBreakerConfig config = context.filters().circuitBreaker();
        if (config == null || config.failureThreshold() <= 0) {
            next.run();
            return;
        }

        String routeId = context.route().id();
        SimpleCircuitBreaker breaker = registry.reconcile(routeId, config);

        if (!breaker.allowRequest()) {
            LOG.debugf("Circuit breaker OPEN for route '%s'", routeId);
            if (metrics != null) {
                metrics.recordCircuitBreakerTriggered();
            }
            FilterResult.stop(503, "Service Unavailable").writeResponse(context.response());
            return;
        }

        // The outcome (success/failure) is reported by the downstream proxy handler
        // via recordOutcome(routeId, success) once the request actually completes.
        // This is intentional: HTTP requests finish synchronously inside next.run()
        // (the proxy awaits the upstream), while WebSocket upgrades complete
        // asynchronously in callbacks. Reporting from the handler — rather than a
        // synchronous post-check here — keeps a single source of truth for both
        // paths and avoids counting a successful async upgrade as a failure.
        next.run();
    }

    public void recordOutcome(String routeId, boolean success) {
        SimpleCircuitBreaker breaker = registry.get(routeId);
        if (breaker != null) {
            if (success) {
                breaker.recordSuccess();
            } else {
                breaker.recordFailure();
            }
        }
    }
}
