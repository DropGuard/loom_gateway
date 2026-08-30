package com.github.dropguard.loom.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.dropguard.loom.model.RouteConfig.RateLimitConfig;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

/**
 * Fixed‑window rate limiter backed by Caffeine.
 *
 * <p>Each bucket corresponds to a client identity (or the route‑wide bucket when no identity is
 * available). Caffeine provides automatic size‑based eviction ({@code maximumSize}) and time‑based
 * expiration ({@code expireAfterWrite}), so the limiter never grows without bound and stale buckets
 * are removed without manual housekeeping.
 */
public class SimpleRateLimiter {

    private static final Logger LOG = Logger.getLogger(SimpleRateLimiter.class);
    private final int limit;
    private final long windowMs;
    private final Cache<String, Bucket> cache;

    /**
     * @param limit max requests per window
     * @param windowMs window length in milliseconds
     * @param maxBuckets maximum number of buckets (client identities) the limiter may hold
     */
    public SimpleRateLimiter(int limit, long windowMs, int maxBuckets) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.cache =
                Caffeine.newBuilder()
                        .maximumSize(maxBuckets)
                        .expireAfterWrite(windowMs, TimeUnit.MILLISECONDS)
                        .build();
    }

    /**
     * Creates a limiter from a {@link RateLimitConfig} and the globally configured {@code
     * gateway.rate-limit.maximum-size}.
     *
     * @param config route‑specific limit/window
     * @param maxBuckets maximum number of buckets allowed
     */
    public SimpleRateLimiter(RateLimitConfig config, int maxBuckets) {
        this(config.limit(), config.windowMs(), maxBuckets);
    }

    /**
     * @param key bucket key; pass a stable constant (e.g. the route id) to get a single route‑wide
     *     bucket, or a per‑client id for fairness.
     */
    public boolean tryAcquire(String key) {
        Bucket bucket = cache.get(key, k -> new Bucket());
        return bucket.tryAcquire(limit, windowMs);
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowMs() {
        return windowMs;
    }

    private static class Bucket {
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

        long windowStart() {
            return windowStart;
        }
    }
}
