package com.github.dropguard.loom.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.model.RouteConfig.RateLimitConfig;

import org.junit.jupiter.api.Test;

/**
 * Fixed-window rate limiter: locks the bucket-exhaustion boundary, the window-reset behavior, and
 * that many distinct keys stay correct as the backing bucket map grows.
 */
class SimpleRateLimiterTest {

    private SimpleRateLimiter limiter(int limit, long windowMs) {
        // Use a sufficiently large bucket limit so the tests are not affected by eviction.
        return new SimpleRateLimiter(new RateLimitConfig(limit, windowMs), 10_000);
    }

    @Test
    void allowsUpToLimit_thenBlocks() {
        SimpleRateLimiter limiter = limiter(3, 1000);
        assertTrue(limiter.tryAcquire("k"));
        assertTrue(limiter.tryAcquire("k"));
        assertTrue(limiter.tryAcquire("k"));
        // 4th acquisition exceeds the limit of 3.
        assertFalse(limiter.tryAcquire("k"));
    }

    @Test
    void separateKeys_haveSeparateBuckets() {
        SimpleRateLimiter limiter = limiter(1, 1000);
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        // A different key gets its own fresh bucket.
        assertTrue(limiter.tryAcquire("b"));
    }

    @Test
    void windowReset_allowsAgain() throws InterruptedException {
        SimpleRateLimiter limiter = limiter(1, 50);
        assertTrue(limiter.tryAcquire("k"));
        assertFalse(limiter.tryAcquire("k"));

        Thread.sleep(70); // window elapses
        assertTrue(limiter.tryAcquire("k"), "bucket should reset after window");
    }

    @Test
    void manyDistinctKeys_stayCorrect() {
        // Drive far more buckets than the configured maximum to confirm that
        // Caffeine eviction does not corrupt per-key acquisition.
        SimpleRateLimiter limiter = limiter(10, 60_000);
        for (int i = 0; i < 1100; i++) {
            String key = "client-" + i;
            for (int j = 0; j < 10; j++) {
                assertTrue(limiter.tryAcquire(key));
            }
            assertFalse(limiter.tryAcquire(key));
        }
    }
}
