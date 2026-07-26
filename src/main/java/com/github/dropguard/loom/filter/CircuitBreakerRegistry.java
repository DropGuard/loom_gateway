package com.github.dropguard.loom.filter;

import com.github.dropguard.loom.model.RouteConfig.CircuitBreakerConfig;

import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * Owns the lifecycle of per-route circuit breakers. Replaces the previous inline {@code
 * ConcurrentHashMap} inside CircuitBreakerFilter so that creation and config-binding live in one
 * place, not scattered in the filter.
 *
 * <p>The map is a plain HashMap guarded by a single monitor lock. Breaker creation is cheap and
 * happens at most once per route, so there is no need for a concurrent collection; the lock only
 * serializes the rare create/reset with the frequent get.
 */
@Singleton
public class CircuitBreakerRegistry {

    private static final Logger LOG = Logger.getLogger(CircuitBreakerRegistry.class);

    private final Map<String, SimpleCircuitBreaker> breakers = new HashMap<>();

    /**
     * Returns the breaker for {@code routeId}, rebuilding it when its bound config has drifted from
     * {@code config} (e.g. the route was hot-reloaded with new thresholds). Rebuilding resets the
     * breaker with the new config. Called on every request so config changes take effect without an
     * explicit reload event; when the config is unchanged it returns the existing breaker with no
     * side effect.
     */
    public synchronized SimpleCircuitBreaker reconcile(
            String routeId, CircuitBreakerConfig config) {
        SimpleCircuitBreaker existing = breakers.get(routeId);
        if (existing == null || !config.equals(existing.config())) {
            if (existing != null) {
                LOG.infof("Rebuilding circuit breaker for route '%s' (config changed)", routeId);
            }
            existing = new SimpleCircuitBreaker(config);
            breakers.put(routeId, existing);
        }
        return existing;
    }

    /** Returns the existing breaker for a route without creating one, or null. */
    public synchronized SimpleCircuitBreaker get(String routeId) {
        return breakers.get(routeId);
    }

    /**
     * Removes a route's breaker so the next request rebuilds it from the current config. Intended
     * for operators recovering a route stuck OPEN, and for tests that leave a breaker in a
     * non-default state. Per-route, never a blanket clear: there is no production need to wipe
     * every route's breaker at once, and doing so would silently mask state leaked by a test.
     */
    public synchronized void reset(String routeId) {
        breakers.remove(routeId);
    }
}
