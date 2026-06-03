package com.gateway.filter;

import com.gateway.metrics.GatewayMetrics;
import com.gateway.model.RouteConfig.CircuitBreakerConfig;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

@Singleton
public class CircuitBreakerFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(CircuitBreakerFilter.class);
    private final Map<String, SimpleCircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final GatewayMetrics metrics;

    @Inject
    public CircuitBreakerFilter(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    public CircuitBreakerFilter() {
        this.metrics = null;
    }

    @Override
    public void apply(FilterContext context, Next next) throws Exception {
        CircuitBreakerConfig config = context.filters().circuitBreaker();
        if (config == null || config.failureThreshold() <= 0) {
            next.run();
            return;
        }

        String routeId = context.route().id();
        SimpleCircuitBreaker breaker = breakers.computeIfAbsent(routeId, k -> new SimpleCircuitBreaker(config));

        if (!breaker.allowRequest()) {
            LOG.debugf("Circuit breaker OPEN for route '%s'", routeId);
            if (metrics != null) metrics.recordCircuitBreakerTriggered();
            FilterResult.stop(503, "Service Unavailable").writeResponse(context.response());
            return;
        }

        try {
            next.run();
        } catch (Exception e) {
            breaker.recordFailure();
            throw e;
        }

        if (context.proxySuccess()) {
            breaker.recordSuccess();
        } else {
            breaker.recordFailure();
        }
    }

    public void recordOutcome(String routeId, boolean success) {
        SimpleCircuitBreaker breaker = breakers.get(routeId);
        if (breaker != null) {
            if (success)
                breaker.recordSuccess();
            else
                breaker.recordFailure();
        }
    }
}
