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
        SimpleRateLimiter limiter = limiters.computeIfAbsent(routeId, k -> new SimpleRateLimiter(config));

        if (limiter.tryAcquire()) {
            next.run();
        } else {
            LOG.debugf("Rate limit exceeded for route '%s'", routeId);
            if (metrics != null) metrics.recordRateLimitExceeded();
            FilterResult.stop(429, "Too Many Requests").writeResponse(context.response());
        }
    }
}
