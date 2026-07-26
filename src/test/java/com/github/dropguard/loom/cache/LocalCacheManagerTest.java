package com.github.dropguard.loom.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalCacheManagerTest {

    private LocalCacheManager cache;

    @BeforeEach
    void setUp() {
        cache = new LocalCacheManager();
    }

    @Test
    void testPutAndGet() {
        String key = "test-key";
        cache.put(key, 200, "application/json", "data".getBytes(), 10);

        LocalCacheManager.CacheEntry entry = cache.get(key);
        assertNotNull(entry);
        assertEquals(200, entry.statusCode());
        assertArrayEquals("data".getBytes(), entry.body());
    }

    @Test
    void testMissingKeyReturnsNull() {
        assertNull(cache.get("non-existent"));
    }

    @Test
    void testEntryExpiresAutomatically() throws InterruptedException {
        String key = "expire-test";
        cache.put(key, 200, "text/plain", "hello".getBytes(), 0); // 0 seconds = expires immediately

        // Entry should be expired and evicted by Caffeine
        assertNull(cache.get(key));
    }

    @Test
    void testInvalidate() {
        String key = "invalidate-test";
        cache.put(key, 200, "json", "data".getBytes(), 10);
        assertNotNull(cache.get(key));

        cache.invalidate(key);
        assertNull(cache.get(key));
    }

    @Test
    void testClear() {
        cache.put("k1", 200, "json", "a".getBytes(), 10);
        cache.put("k2", 200, "json", "b".getBytes(), 10);

        cache.clear();
        assertTrue(cache.asMap().isEmpty());
    }
}
