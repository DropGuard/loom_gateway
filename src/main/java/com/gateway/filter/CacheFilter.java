package com.gateway.filter;

import org.jboss.logging.Logger;

import com.gateway.cache.LocalCacheManager;
import com.gateway.cache.LocalCacheManager.CacheEntry;
import com.gateway.model.RouteConfig.CacheConfig;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Caches GET responses in memory.
 * On hit: stores CacheEntry on context for the router to write.
 * On miss: stores cacheKey/cacheConfig for storeResponse() after proxy.
 */
@Singleton
public class CacheFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(CacheFilter.class);
    private final LocalCacheManager cache;

    @Inject
    public CacheFilter(Instance<LocalCacheManager> cacheInstance) {
        this.cache = cacheInstance.isResolvable() ? cacheInstance.get() : null;
    }

    public CacheFilter() {
        this.cache = null;
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public FilterResult filter(FilterContext context) {
        CacheConfig config = context.route() != null ? context.route().cache() : null;

        if (cache == null || config == null || !config.enabled() || config.ttlSeconds() <= 0) {
            return FilterResult.CONTINUE;
        }

        String method = context.request().method() != null ? context.request().method().name() : "GET";
        if (!"GET".equalsIgnoreCase(method)) {
            return FilterResult.CONTINUE;
        }

        String cacheKey = buildKey(context);
        CacheEntry entry = cache.get(cacheKey);

        if (entry != null) {
            LOG.debugf("Cache hit for '%s'", cacheKey);
            context.setAttribute("cachedResponse", entry);
            return FilterResult.CONTINUE;
        }

        // Cache miss — mark for later population
        context.setAttribute("cacheKey", cacheKey);
        context.setAttribute("cacheConfig", config);
        return FilterResult.CONTINUE;
    }

    /**
     * Called after a successful backend response to populate the cache.
     */
    public void storeResponse(FilterContext context) {
        String key = context.getAttribute("cacheKey");
        CacheConfig config = context.getAttribute("cacheConfig");
        if (key == null || config == null || cache == null) {
            return;
        }
        // Response body must have been captured by the proxy (future enhancement)
        // For now, this is a hook for when response body buffering is added
    }

    private String buildKey(FilterContext context) {
        String method = context.request().method().name();
        String uri = context.request().uri();
        return method + ":" + uri;
    }
}
