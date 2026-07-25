package com.gateway.filter;

import com.gateway.model.RouteConfig.CircuitBreakerConfig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.logging.Logger;

enum CircuitState {
    CLOSED, OPEN, HALF_OPEN
}

public class SimpleCircuitBreaker {

    private static final Logger LOG = Logger.getLogger(SimpleCircuitBreaker.class);

    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final int failureThreshold;
    private final long resetTimeoutMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public SimpleCircuitBreaker(CircuitBreakerConfig config) {
        this.failureThreshold = config.failureThreshold();
        this.resetTimeoutMs = config.resetTimeoutMs();
    }

    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        CircuitState currentState = state.get();

        if (currentState == CircuitState.CLOSED) {
            return true;
        }

        if (currentState == CircuitState.OPEN) {
            if (now - lastFailureTime.get() >= resetTimeoutMs) {
                if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    LOG.debug("Circuit breaker transitioning to HALF_OPEN");
                    return true;
                }
            }
            return false;
        }

        return true;
    }

    public void recordSuccess() {
        CircuitState current = state.get();
        if (current == CircuitState.HALF_OPEN) {
            state.set(CircuitState.CLOSED);
            consecutiveFailures.set(0);
            LOG.debug("Circuit breaker transitioning to CLOSED");
        }
    }

    public void recordFailure() {
        CircuitState current = state.get();

        // Only (re)arm the OPEN timer when we actually transition into OPEN.
        // Updating lastFailureTime on every failure would keep pushing the
        // HALF_OPEN deadline forward, so under a steady trickle of failures the
        // breaker would stay OPEN forever and serve 100% 503s. (DEFECT #7)
        if (current == CircuitState.HALF_OPEN) {
            // A probe failed: go back to OPEN and restart the timer.
            consecutiveFailures.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            state.set(CircuitState.OPEN);
            LOG.debug("Circuit breaker transitioning back to OPEN after probe failure");
            return;
        }

        // CLOSED (or OPEN): just count. The timer is armed only on the
        // CLOSED -> OPEN transition below, never for failures while already OPEN.
        int failures = consecutiveFailures.incrementAndGet();
        if (current == CircuitState.CLOSED && failures >= failureThreshold) {
            if (state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                lastFailureTime.set(System.currentTimeMillis());
                LOG.debugf("Circuit breaker transitioning to OPEN (failures: %d)", failures);
            }
        }
    }

    public CircuitState getState() {
        return state.get();
    }
}
