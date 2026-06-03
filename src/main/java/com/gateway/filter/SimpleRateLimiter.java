package com.gateway.filter;

import com.gateway.model.RouteConfig.RateLimitConfig;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe fixed-window rate limiter per route. Uses ReentrantLock instead
 * of synchronized to avoid pinning virtual threads.
 */
public class SimpleRateLimiter {

    private final int limit;
    private final long windowMs;
    private final AtomicLong counter = new AtomicLong(0);
    private volatile long windowStart;
    private final ReentrantLock lock = new ReentrantLock();

    public SimpleRateLimiter(RateLimitConfig config) {
        this.limit = config.limit();
        this.windowMs = config.windowMs();
        this.windowStart = System.currentTimeMillis();
    }

    public boolean tryAcquire() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                windowStart = now;
                counter.set(0);
            }
            return counter.incrementAndGet() <= limit;
        } finally {
            lock.unlock();
        }
    }
}
