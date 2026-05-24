package com.gateway.filter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class FilterChainProducer {

    private static final Logger LOG = Logger.getLogger(FilterChainProducer.class);

    @Produces
    @Singleton
    @Named("http")
    FilterChain httpChain(AuthFilter auth, RateLimitFilter rateLimit,
                          CircuitBreakerFilter circuitBreaker, GrayReleaseFilter gray,
                          CacheFilter cache) {
        FilterChain chain = new FilterChain(List.of(auth, rateLimit, circuitBreaker, gray, cache));
        LOG.infof("HTTP filter chain: %d filters", chain.size());
        return chain;
    }

    @Produces
    @Singleton
    @Named("ws")
    FilterChain wsChain(AuthFilter auth, RateLimitFilter rateLimit,
                        CircuitBreakerFilter circuitBreaker, GrayReleaseFilter gray) {
        FilterChain chain = new FilterChain(List.of(auth, rateLimit, circuitBreaker, gray));
        LOG.infof("WebSocket filter chain: %d filters", chain.size());
        return chain;
    }
}
