package com.github.dropguard.loom.filter;

import com.github.dropguard.loom.model.RouteConfig.CircuitBreakerConfig;

import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

/**
 * Owns the lifecycle of per-route circuit breakers. Uses a ConcurrentHashMap with lock-free reads
 * for high-throughput concurrency.
 */
@Singleton
public class CircuitBreakerRegistry {

    private static final Logger LOG = Logger.getLogger(CircuitBreakerRegistry.class);

    private final ConcurrentHashMap<String, SimpleCircuitBreaker> breakers =
            new ConcurrentHashMap<>();

    /**
     * Returns the breaker for {@code routeId}, rebuilding it when its bound config has drifted from
     * {@code config} (e.g. the route was hot-reloaded with new thresholds).
     */
    public SimpleCircuitBreaker reconcile(String routeId, CircuitBreakerConfig config) {
        SimpleCircuitBreaker existing = breakers.get(routeId);
        if (existing != null && config.equals(existing.config())) {
            return existing;
        }

        return breakers.compute(
                routeId,
                (k, current) -> {
                    if (current == null || !config.equals(current.config())) {
                        if (current != null) {
                            LOG.infof(
                                    "Rebuilding circuit breaker for route '%s' (config changed)",
                                    routeId);
                        }
                        return new SimpleCircuitBreaker(config);
                    }
                    return current;
                });
    }

    /** Returns the existing breaker for a route without creating one, or null. Lock-free. */
    public SimpleCircuitBreaker get(String routeId) {
        return breakers.get(routeId);
    }

    /** Removes a route's breaker. */
    public void reset(String routeId) {
        breakers.remove(routeId);
    }
}
