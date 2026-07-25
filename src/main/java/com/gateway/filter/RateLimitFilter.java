package com.gateway.filter;

import com.gateway.metrics.GatewayMetrics;
import com.gateway.model.RouteConfig.RateLimitConfig;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

@Singleton
public class RateLimitFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    private final Map<String, SimpleRateLimiter> limiters = new ConcurrentHashMap<>();
    private final GatewayMetrics metrics;

    @Inject
    public RateLimitFilter(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    public RateLimitFilter() {
        this.metrics = null;
    }

    @Override
    public void apply(FilterContext context, Next next) throws Exception {
        RateLimitConfig config = context.filters().rateLimit();
        if (config == null || config.limit() <= 0) {
            next.run();
            return;
        }

        String routeId = context.route().id();
        // Per-client bucket (DEFECT #8): each client gets its own quota so one
        // client cannot exhaust the whole route. clientId is null for routes
        // with no auth, which falls back to the route-wide bucket (key == routeId,
        // unchanged behavior for anonymous traffic).
        String clientId = resolveClientId(context);
        String bucketKey = clientId != null ? routeId + ":" + clientId : routeId;
        SimpleRateLimiter limiter = limiters.computeIfAbsent(bucketKey, k -> new SimpleRateLimiter(config));

        if (limiter.tryAcquire(bucketKey)) {
            next.run();
        } else {
            LOG.debugf("Rate limit exceeded for route '%s'%s",
                    routeId, clientId != null ? " (client " + clientId + ")" : "");
            if (metrics != null) metrics.recordRateLimitExceeded();
            FilterResult.stop(429, "Too Many Requests").writeResponse(context.response());
        }
    }

    /**
     * Resolve a stable per-client identity for rate limiting.
     * Priority: JWT "sub" claim > API key header > null (no identity, falls
     * back to the route-wide bucket). Does NOT use raw client IP so we don't
     * depend on X-Forwarded-For trust (a spoofable header).
     */
    private String resolveClientId(FilterContext context) {
        Map<String, Object> claims = context.claims();
        if (claims != null && claims.get("sub") != null) {
            return "sub:" + claims.get("sub");
        }
        String apiKey = context.request().getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            return "key:" + Integer.toHexString(apiKey.hashCode());
        }
        return null;
    }
}
