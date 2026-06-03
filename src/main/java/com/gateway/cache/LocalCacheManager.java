package com.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

/**
 * Manages in-memory caching for GET responses using Caffeine. Uses W-TinyLFU
 * eviction (better hit rate than pure LRU) and native per-key TTL via Caffeine
 * Expiry.
 */
@Singleton
public class LocalCacheManager {

    private static final Logger LOG = Logger.getLogger(LocalCacheManager.class);
    private final Cache<String, CacheEntry> cache;

    public LocalCacheManager() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .executor(Runnable::run)
                .expireAfter(new Expiry<String, CacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CacheEntry entry, long currentTime) {
                        return entry.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CacheEntry entry, long currentTime,
                            long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, CacheEntry entry, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
        LOG.info("LocalCacheManager initialized (max entries: 10000, W-TinyLFU eviction, per-key TTL)");
    }

    public CacheEntry get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, int statusCode, String contentType, byte[] body, long ttlSeconds) {
        long ttlNanos = TimeUnit.SECONDS.toNanos(ttlSeconds);
        LOG.debugf("Caching response for key '%s' with TTL %ds", key, ttlSeconds);
        cache.put(key, new CacheEntry(statusCode, contentType, body, ttlNanos));
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public void clear() {
        cache.invalidateAll();
    }

    public Map<String, CacheEntry> asMap() {
        return cache.asMap();
    }

    public record CacheEntry(int statusCode, String contentType, byte[] body, long ttlNanos) {
    }
}
