package com.gateway.filter;

import com.gateway.model.RouteConfig.RateLimitConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

/**
 * Fixed-window rate limiter. Supports per-key buckets (e.g. one bucket per
 * client identity) so a single client cannot exhaust the whole route's quota.
 * Expired buckets are lazily evicted to keep the map bounded (DEFECT #20 sibling).
 */
public class SimpleRateLimiter {

    private static final Logger LOG = Logger.getLogger(SimpleRateLimiter.class);

    private static final int EVICT_THRESHOLD = 1024;

    private final int limit;
    private final long windowMs;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public SimpleRateLimiter(RateLimitConfig config) {
        this.limit = config.limit();
        this.windowMs = config.windowMs();
    }

    /**
     * @param key bucket key; pass a stable constant (e.g. the route id) to get
     *            a single route-wide bucket, or a per-client id for fairness.
     */
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        boolean allowed = bucket.tryAcquire(limit, windowMs);
        if (buckets.size() > EVICT_THRESHOLD) {
            evictExpired();
        }
        return allowed;
    }

    /**
     * Drop buckets whose window has fully elapsed and have no in-flight count,
     * so the map does not grow without bound under many distinct clients.
     */
    void evictExpired() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e ->
                now - e.getValue().windowStart() >= windowMs && e.getValue().count() == 0);
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowMs() {
        return windowMs;
    }

    private static final class Bucket {
        private final AtomicInteger counter = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        synchronized boolean tryAcquire(int limit, long windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                counter.set(0);
                windowStart = now;
            }
            return counter.incrementAndGet() <= limit;
        }

        int count() {
            return counter.get();
        }

        long windowStart() {
            return windowStart;
        }
    }
}
