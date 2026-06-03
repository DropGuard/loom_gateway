package com.gateway.filter;

import com.gateway.cache.LocalCacheManager;
import com.gateway.cache.LocalCacheManager.CacheEntry;
import com.gateway.metrics.GatewayMetrics;
import com.gateway.model.RouteConfig.CacheConfig;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.jboss.logging.Logger;

@Singleton
public class CacheFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(CacheFilter.class);
    private static final long MAX_CACHE_BODY_SIZE = 1024 * 1024; // 1MB
    private final LocalCacheManager cache;
    private final GatewayMetrics metrics;

    @Inject
    public CacheFilter(Instance<LocalCacheManager> cacheInstance, GatewayMetrics metrics) {
        this.cache = cacheInstance.isResolvable() ? cacheInstance.get() : null;
        this.metrics = metrics;
    }

    public CacheFilter() {
        this.cache = null;
        this.metrics = null;
    }

    @Override
    public void apply(FilterContext context, Next next) throws Exception {
        CacheConfig config = context.filters().cache();

        if (cache == null || config == null || !config.enabled() || config.ttlSeconds() <= 0) {
            next.run();
            return;
        }

        String method = context.request().method() != null ? context.request().method().name() : "GET";
        if (!"GET".equalsIgnoreCase(method)) {
            next.run();
            return;
        }

        String cacheKey = buildKey(context);
        CacheEntry entry = cache.get(cacheKey);

        if (entry != null) {
            LOG.debugf("Cache hit for '%s'", cacheKey);
            if (metrics != null) metrics.recordCacheHit();
            writeCachedResponse(context, entry);
            return;
        }

        if (metrics != null) metrics.recordCacheMiss();

        long ttlSeconds = config.ttlSeconds();

        // Register response interceptor to cache the upstream response
        context.onResponse(data -> {
            if (data.body().length > MAX_CACHE_BODY_SIZE) {
                LOG.debugf("Response too large to cache (%d bytes > %d)",
                        data.body().length, MAX_CACHE_BODY_SIZE);
                return;
            }
            cache.put(cacheKey, data.statusCode(), data.contentType(), data.body(), ttlSeconds);
            LOG.debugf("Cached response for '%s' (%d bytes)", cacheKey, data.body().length);
        });

        next.run();
    }

    private void writeCachedResponse(FilterContext context, CacheEntry entry) {
        context.response().setStatusCode(entry.statusCode());
        if (entry.contentType() != null) {
            context.response().headers().set("content-type", entry.contentType());
        }
        context.response().end(io.vertx.core.buffer.Buffer.buffer(entry.body()));
        LOG.debugf("Served cached response (%d bytes)", entry.body().length);
    }

    private String buildKey(FilterContext context) {
        String method = context.request().method().name();
        String uri = context.request().uri();
        String identity = getUserIdentity(context);
        if (identity != null) {
            return method + ":" + uri + ":" + identity;
        }
        return method + ":" + uri;
    }

    private String getUserIdentity(FilterContext context) {
        // JWT auth: use "sub" claim
        Map<String, Object> claims = context.claims();
        if (claims != null && claims.get("sub") != null) {
            return claims.get("sub").toString();
        }

        // API Key auth: hash the key to avoid leaking it in cache
        String authType = context.filters().auth() != null ? context.filters().auth().type() : null;
        if ("api-key".equalsIgnoreCase(authType)) {
            String apiKey = context.request().getHeader("X-API-Key");
            if (apiKey != null && !apiKey.isEmpty()) {
                return hashApiKey(apiKey);
            }
        }

        return null;
    }

    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is always available, this should never happen
            return apiKey;
        }
    }
}
