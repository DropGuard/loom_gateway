package com.benchmark.scg;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window in-memory rate limiter, mirrored after the Loom Gateway
 * benchmark's {@code SimpleRateLimiter} so both sides run the same semantics:
 * a per-route bucket, {@code limit} requests per {@code windowMs} window.
 * No Redis — keeps the comparison local and symmetric.
 */
@Component
public class RateLimitGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimitGatewayFilterFactory.Config> {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // The matched route is carried as a Route object on this attribute.
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route != null ? route.getId() : "default";
            Bucket bucket = buckets.computeIfAbsent(routeId, k -> new Bucket());
            if (!bucket.tryAcquire(config.limit, config.windowMs)) {
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }

    public static class Config {
        public int limit = 1000;
        public int windowMs = 1000;

        public Config() {
        }

        public RateLimitGatewayFilterFactory.Config setLimit(int limit) {
            this.limit = limit;
            return this;
        }

        public RateLimitGatewayFilterFactory.Config setWindowMs(int windowMs) {
            this.windowMs = windowMs;
            return this;
        }
    }

    private static final class Bucket {
        private final AtomicInteger counter = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        synchronized boolean tryAcquire(int limit, int windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                counter.set(0);
                windowStart = now;
            }
            return counter.incrementAndGet() <= limit;
        }
    }
}
