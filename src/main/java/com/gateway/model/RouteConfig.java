package com.gateway.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RouteConfig(
        String id,
        String path,
        List<String> methods,
        String upstream,
        FilterConfig filters) {

    public RouteConfig {
        if (filters == null) filters = FilterConfig.EMPTY;
    }

    public record FilterConfig(
            AuthConfig auth,
            RateLimitConfig rateLimit,
            CircuitBreakerConfig circuitBreaker,
            CacheConfig cache,
            GrayConfig gray) {

        public static final FilterConfig EMPTY = new FilterConfig(null, null, null, null, null);

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private AuthConfig auth;
            private RateLimitConfig rateLimit;
            private CircuitBreakerConfig circuitBreaker;
            private CacheConfig cache;
            private GrayConfig gray;

            public Builder auth(AuthConfig auth) { this.auth = auth; return this; }
            public Builder rateLimit(RateLimitConfig rateLimit) { this.rateLimit = rateLimit; return this; }
            public Builder circuitBreaker(CircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; return this; }
            public Builder cache(CacheConfig cache) { this.cache = cache; return this; }
            public Builder gray(GrayConfig gray) { this.gray = gray; return this; }

            public FilterConfig build() {
                return new FilterConfig(auth, rateLimit, circuitBreaker, cache, gray);
            }
        }
    }

    public record AuthConfig(
            String type,
            boolean required,
            String secret,
            List<String> apiKeys) {
    }

    public record RateLimitConfig(
            int limit,
            long windowMs) {
    }

    public record CircuitBreakerConfig(
            int failureThreshold,
            long resetTimeoutMs) {
    }

    public record CacheConfig(
            boolean enabled,
            int ttlSeconds) {
    }

    public record GrayConfig(
            @JsonProperty("trafficPercent") double trafficPercent,
            @JsonProperty("grayUpstream") String grayUpstream,
            @JsonProperty("headerMatch") List<String> headerMatch) {
    }
}
