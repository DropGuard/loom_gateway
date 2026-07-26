package com.github.dropguard.loom.filter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

import org.jboss.logging.Logger;

@Singleton
public class FilterChains {

    private static final Logger LOG = Logger.getLogger(FilterChains.class);
    private final FilterChain http;
    private final FilterChain ws;

    @Inject
    public FilterChains(
            AuthFilter auth,
            RateLimitFilter rateLimit,
            CircuitBreakerFilter circuitBreaker,
            GrayReleaseFilter gray,
            CacheFilter cache) {
        this.http = new FilterChain(List.of(auth, rateLimit, circuitBreaker, gray, cache));
        this.ws = new FilterChain(List.of(auth, rateLimit, circuitBreaker, gray));
        LOG.infof(
                "HTTP filter chain: %d filters, WebSocket filter chain: %d filters",
                http.size(), ws.size());
    }

    public FilterChain http() {
        return http;
    }

    public FilterChain ws() {
        return ws;
    }
}
