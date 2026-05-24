package com.gateway.filter;

import com.gateway.model.RouteConfig.CircuitBreakerConfig;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies circuit breaking based on route configuration.
 * Maintains a circuit breaker per route.
 */
@Singleton
public class CircuitBreakerFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(CircuitBreakerFilter.class);
    private final Map<String, SimpleCircuitBreaker> breakers = new ConcurrentHashMap<>();

    @Override
    public FilterResult filter(FilterContext context) {
        CircuitBreakerConfig config = context.filters().circuitBreaker();
        if (config == null || config.failureThreshold() <= 0) {
            return FilterResult.CONTINUE;
        }

        String routeId = context.route().id();
        SimpleCircuitBreaker breaker = breakers.computeIfAbsent(routeId, k -> new SimpleCircuitBreaker(config));

        if (!breaker.allowRequest()) {
            LOG.debugf("Circuit breaker OPEN for route '%s'", routeId);
            return FilterResult.stop(503, "Service Unavailable");
        }

        return FilterResult.CONTINUE;
    }

    /**
     * Called by the router/proxy after a backend response is received,
     * to record success or failure of the request.
     */
    public void recordOutcome(String routeId, boolean success) {
        SimpleCircuitBreaker breaker = breakers.get(routeId);
        if (breaker != null) {
            if (success) breaker.recordSuccess();
            else breaker.recordFailure();
        }
    }
}
