package com.github.dropguard.loom.filter;

import com.github.dropguard.loom.model.RouteConfig.CircuitBreakerConfig;

import org.jboss.logging.Logger;

enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

/**
 * Per-route circuit breaker state machine. All transitions are guarded by a single monitor lock:
 * the state is pure in-memory and microsecond-scale, so a plain synchronized block is both correct
 * and clearer than compare-and-set gymnastics. The lock also provides single-flight into HALF_OPEN
 * for free.
 */
public class SimpleCircuitBreaker {

    private static final Logger LOG = Logger.getLogger(SimpleCircuitBreaker.class);

    private final CircuitBreakerConfig config;
    private final long failureThreshold;
    private final long resetTimeoutMs;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures = 0;
    private long lastFailureTime = 0;

    public SimpleCircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
        this.failureThreshold = config.failureThreshold();
        this.resetTimeoutMs = config.resetTimeoutMs();
    }

    public synchronized boolean allowRequest() {
        long now = System.currentTimeMillis();
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (now - lastFailureTime >= resetTimeoutMs) {
                    // Single-flight: only the first caller past the timeout window
                    // transitions to HALF_OPEN and acts as the probe.
                    state = CircuitState.HALF_OPEN;
                    LOG.debug("Circuit breaker transitioning to HALF_OPEN");
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> true;
        };
    }

    public synchronized void recordSuccess() {
        if (state == CircuitState.HALF_OPEN) {
            state = CircuitState.CLOSED;
            consecutiveFailures = 0;
            LOG.debug("Circuit breaker transitioning to CLOSED");
        }
    }

    public synchronized void recordFailure() {
        if (state == CircuitState.HALF_OPEN) {
            // A probe failed: back to OPEN and restart the timer. Restarting
            // (rather than updating on every failure) avoids a steady trickle of
            // failures keeping the breaker OPEN forever. (DEFECT #7)
            state = CircuitState.OPEN;
            lastFailureTime = System.currentTimeMillis();
            LOG.debug("Circuit breaker transitioning back to OPEN after probe failure");
            return;
        }

        // CLOSED or OPEN: the timer is armed only on the CLOSED -> OPEN
        // transition below, never while already OPEN.
        consecutiveFailures++;
        if (state == CircuitState.CLOSED && consecutiveFailures >= failureThreshold) {
            state = CircuitState.OPEN;
            lastFailureTime = System.currentTimeMillis();
            LOG.debugf("Circuit breaker transitioning to OPEN (failures: %d)", consecutiveFailures);
        }
    }

    public synchronized CircuitState getState() {
        return state;
    }

    public CircuitBreakerConfig config() {
        return config;
    }
}
