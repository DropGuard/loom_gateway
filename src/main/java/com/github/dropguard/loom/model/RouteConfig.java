package com.github.dropguard.loom.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RouteConfig(
        String id, String path, List<String> methods, String upstream, FilterConfig filters) {

    public RouteConfig {
        if (filters == null) filters = FilterConfig.EMPTY;
    }

    public record FilterConfig(
            AuthConfig auth,
            RateLimitConfig rateLimit,
            CircuitBreakerConfig circuitBreaker,
            CacheConfig cache,
            GrayConfig gray,
            TimeoutConfig timeout,
            ClaimsForwardConfig claimsForward) {

        public static final FilterConfig EMPTY =
                new FilterConfig(null, null, null, null, null, null, null);

        public static Builder builder() {
            return new Builder();
        }

        /** Claims mirrored to downstream request headers (defaults to [sub]). */
        public List<String> forwardClaims() {
            return claimsForward != null && claimsForward.claims() != null
                    ? claimsForward.claims()
                    : List.of("sub");
        }

        public static class Builder {
            private AuthConfig auth;
            private RateLimitConfig rateLimit;
            private CircuitBreakerConfig circuitBreaker;
            private CacheConfig cache;
            private GrayConfig gray;
            private TimeoutConfig timeout;
            private ClaimsForwardConfig claimsForward;

            public Builder auth(AuthConfig auth) {
                this.auth = auth;
                return this;
            }

            public Builder rateLimit(RateLimitConfig rateLimit) {
                this.rateLimit = rateLimit;
                return this;
            }

            public Builder circuitBreaker(CircuitBreakerConfig circuitBreaker) {
                this.circuitBreaker = circuitBreaker;
                return this;
            }

            public Builder cache(CacheConfig cache) {
                this.cache = cache;
                return this;
            }

            public Builder gray(GrayConfig gray) {
                this.gray = gray;
                return this;
            }

            public Builder timeout(TimeoutConfig timeout) {
                this.timeout = timeout;
                return this;
            }

            public Builder claimsForward(ClaimsForwardConfig claimsForward) {
                this.claimsForward = claimsForward;
                return this;
            }

            public FilterConfig build() {
                return new FilterConfig(
                        auth, rateLimit, circuitBreaker, cache, gray, timeout, claimsForward);
            }
        }
    }

    public record AuthConfig(String type, boolean required, String secret, List<String> apiKeys) {}

    public record RateLimitConfig(int limit, long windowMs) {}

    public record CircuitBreakerConfig(int failureThreshold, long resetTimeoutMs) {}

    public record CacheConfig(boolean enabled, int ttlSeconds) {}

    public record TimeoutConfig(int timeoutMs) {}

    public record ClaimsForwardConfig(List<String> claims) {}

    public record GrayConfig(
            @JsonProperty("mode") String mode,
            @JsonProperty("grayUpstream") String grayUpstream,
            @JsonProperty("percent") Double percent,
            @JsonProperty("ruleHeader") String ruleHeader,
            @JsonProperty("ruleValue") String ruleValue) {

        /** Percentage-based canary: a deterministic fraction of authenticated traffic. */
        public static final String MODE_PERCENTAGE = "percentage";

        /** Rule-based canary: a trusted layer opts specific requests in via a header. */
        public static final String MODE_RULE = "rule";

        public boolean isPercentage() {
            return MODE_PERCENTAGE.equalsIgnoreCase(mode);
        }

        public boolean isRule() {
            return MODE_RULE.equalsIgnoreCase(mode);
        }

        public boolean isEnabled() {
            return grayUpstream() != null && (isPercentage() || isRule());
        }

        public double effectivePercent() {
            return Math.min(Math.max(percent != null ? percent : 0, 0), 100);
        }
    }
}
