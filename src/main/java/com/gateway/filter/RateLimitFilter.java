package com.gateway.filter;

import com.gateway.model.RouteConfig.RateLimitConfig;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies rate limiting based on route configuration.
 * Uses a fixed-window counter per route.
 */
@Singleton
public class RateLimitFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    private final Map<String, SimpleRateLimiter> limiters = new ConcurrentHashMap<>();

    @Override
    public FilterResult filter(FilterContext context) {
        RateLimitConfig config = context.filters().rateLimit();
        if (config == null || config.limit() <= 0) {
            return FilterResult.CONTINUE;
        }

        String routeId = context.route().id();
        SimpleRateLimiter limiter = limiters.computeIfAbsent(routeId, k -> new SimpleRateLimiter(config));

        if (limiter.tryAcquire()) {
            return FilterResult.CONTINUE;
        }

        LOG.debugf("Rate limit exceeded for route '%s'", routeId);
        return FilterResult.stop(429, "Too Many Requests");
    }
}
