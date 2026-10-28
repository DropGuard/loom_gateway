package com.github.dropguard.loom.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

/**
 * Manages in-memory caching for GET responses using Caffeine. Uses W-TinyLFU eviction (better hit
 * rate than pure LRU) and native per-key TTL via Caffeine Expiry.
 */
@Singleton
public class LocalCacheManager {

    private static final Logger LOG = Logger.getLogger(LocalCacheManager.class);

    /** Total payload budget, not an entry count: 10k x 1MB entries would not fit a 256MB heap. */
    private static final long DEFAULT_MAX_WEIGHT_BYTES = 64L * 1024 * 1024;

    private static final int ENTRY_OVERHEAD_BYTES = 256;

    private final Cache<String, CacheEntry> cache;

    public LocalCacheManager() {
        this(DEFAULT_MAX_WEIGHT_BYTES);
    }

    LocalCacheManager(long maxWeightBytes) {
        this.cache =
                Caffeine.newBuilder()
                        .maximumWeight(Math.max(1, maxWeightBytes))
                        .weigher(
                                (String key, CacheEntry entry) -> {
                                    long weight =
                                            (long) entry.body().length
                                                    + (long) key.length() * 2
                                                    + ENTRY_OVERHEAD_BYTES;
                                    return (int) Math.min(Integer.MAX_VALUE, weight);
                                })
                        .executor(Runnable::run)
                        .expireAfter(
                                new Expiry<String, CacheEntry>() {
                                    @Override
                                    public long expireAfterCreate(
                                            String key, CacheEntry entry, long currentTime) {
                                        return entry.ttlNanos();
                                    }

                                    @Override
                                    public long expireAfterUpdate(
                                            String key,
                                            CacheEntry entry,
                                            long currentTime,
                                            long currentDuration) {
                                        return currentDuration;
                                    }

                                    @Override
                                    public long expireAfterRead(
                                            String key,
                                            CacheEntry entry,
                                            long currentTime,
                                            long currentDuration) {
                                        return currentDuration;
                                    }
                                })
                        .build();
        LOG.infof(
                "LocalCacheManager initialized (max weight: %d bytes, W-TinyLFU eviction, "
                        + "per-key TTL)",
                maxWeightBytes);
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

    public record CacheEntry(int statusCode, String contentType, byte[] body, long ttlNanos) {}
}
