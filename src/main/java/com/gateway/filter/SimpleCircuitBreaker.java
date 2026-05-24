package com.gateway.filter;

import com.gateway.model.RouteConfig.CircuitBreakerConfig;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
        consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        CircuitState current = state.get();
        if (current == CircuitState.HALF_OPEN) {
            state.set(CircuitState.OPEN);
            LOG.debug("Circuit breaker transitioning back to OPEN after probe failure");
        } else if (consecutiveFailures.get() >= failureThreshold) {
            state.set(CircuitState.OPEN);
            LOG.debugf("Circuit breaker transitioning to OPEN (failures: %d)", consecutiveFailures.get());
        }
    }

    public CircuitState getState() {
        return state.get();
    }
}
