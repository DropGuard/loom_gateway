package com.github.dropguard.loom.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.model.RouteConfig.RateLimitConfig;

import org.junit.jupiter.api.Test;

/**
 * Fixed-window rate limiter: locks the bucket-exhaustion boundary, the window-reset behavior, and
 * the lazy eviction path that keeps the bucket map bounded under many distinct keys (DEFECT #20
 * sibling).
 */
class SimpleRateLimiterTest {

    private SimpleRateLimiter limiter(int limit, long windowMs) {
        return new SimpleRateLimiter(new RateLimitConfig(limit, windowMs));
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
    void evictionTriggerPath_runsUnderManyDistinctKeys() {
        // Drive more than EVICT_THRESHOLD (1024) distinct buckets to exercise
        // the lazy eviction branch; no assertion on eviction itself, just that
        // acquisition stays correct and does not throw.
        SimpleRateLimiter limiter = limiter(10, 60_000);
        for (int i = 0; i < 1100; i++) {
            String key = "client-" + i;
            for (int j = 0; j < 10; j++) {
                assertTrue(limiter.tryAcquire(key));
            }
            assertFalse(limiter.tryAcquire(key));
        }
    }

    @Test
    void exposesConfiguredLimitAndWindow() {
        SimpleRateLimiter limiter = limiter(7, 5000);
        assertEquals(7, limiter.getLimit());
        assertEquals(5000, limiter.getWindowMs());
    }
}
