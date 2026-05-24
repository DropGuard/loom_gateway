package com.gateway.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Route configuration model loaded from routes.yaml
 */
public record RouteConfig(
        String id,
        String path,
        List<String> methods,
        String upstream,
        AuthConfig auth,
        RateLimitConfig rateLimit,
        CircuitBreakerConfig circuitBreaker,
        CacheConfig cache,
        GrayConfig gray) {
    public record AuthConfig(
            String type, // "jwt", "api-key"
            boolean required,
            String secret, // for JWT: HMAC secret
            List<String> apiKeys // for API Key: list of valid keys
    ) {
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
