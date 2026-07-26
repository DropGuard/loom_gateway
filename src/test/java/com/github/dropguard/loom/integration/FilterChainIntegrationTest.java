package com.github.dropguard.loom.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.filter.*;
import com.github.dropguard.loom.metrics.GatewayMetrics;
import com.github.dropguard.loom.model.RouteConfig;
import com.github.dropguard.loom.model.RouteConfig.AuthConfig;
import com.github.dropguard.loom.model.RouteConfig.CacheConfig;
import com.github.dropguard.loom.model.RouteConfig.CircuitBreakerConfig;
import com.github.dropguard.loom.model.RouteConfig.FilterConfig;
import com.github.dropguard.loom.model.RouteConfig.GrayConfig;
import com.github.dropguard.loom.model.RouteConfig.RateLimitConfig;
import com.github.dropguard.loom.testutil.MockRequest;
import com.github.dropguard.loom.testutil.MockResponse;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.http.HttpMethod;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FilterChainIntegrationTest {

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    private AuthFilter authFilter;
    private RateLimitFilter rateLimitFilter;
    private CircuitBreakerFilter circuitBreakerFilter;
    private CacheFilter cacheFilter;

    @BeforeEach
    void setUp() {
        authFilter = new AuthFilter();
        rateLimitFilter = new RateLimitFilter();
        circuitBreakerFilter = new CircuitBreakerFilter(new CircuitBreakerRegistry(), new GatewayMetrics(new SimpleMeterRegistry()));
        cacheFilter = new CacheFilter();
    }

    @Test
    void testAuthBlocksBeforeRateLimit() throws Exception {
        RouteConfig route = new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder()
                        .auth(new AuthConfig("jwt", true, JWT_SECRET, null))
                        .rateLimit(new RateLimitConfig(1000, 1000))
                        .build());
        FilterContext ctx = newTestContext(route);

        AtomicBoolean nextCalled = new AtomicBoolean(false);
        authFilter.apply(ctx, () -> nextCalled.set(true));
        assertFalse(nextCalled.get());
        assertEquals(401, ((MockResponse) ctx.response()).statusCode);
    }

    @Test
    void testCacheFilterSkipsNonGetRequests() throws Exception {
        MockRequest req = new MockRequest();
        req.method = HttpMethod.POST;
        MockResponse resp = new MockResponse();
        FilterContext ctx = new FilterContext(req, resp);

        RouteConfig route = new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder().cache(new CacheConfig(true, 60)).build());
        ctx.route(route);

        AtomicBoolean nextCalled = new AtomicBoolean(false);
        cacheFilter.apply(ctx, () -> nextCalled.set(true));
        assertTrue(nextCalled.get());
    }

    @Test
    void testCircuitBreakerBlocksAfterFailures() throws Exception {
        RouteConfig route = new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder().circuitBreaker(new CircuitBreakerConfig(3, 5000)).build());

        for (int i = 0; i < 3; i++) {
            FilterContext ctx = newTestContext(route);
            circuitBreakerFilter.apply(ctx, () -> circuitBreakerFilter.recordOutcome("r1", false));
        }

        FilterContext ctx2 = newTestContext(route);
        AtomicBoolean nextCalled2 = new AtomicBoolean(false);
        circuitBreakerFilter.apply(ctx2, () -> nextCalled2.set(true));
        assertFalse(nextCalled2.get());
        assertEquals(503, ((MockResponse) ctx2.response()).statusCode);
    }

    @Test
    void testGrayReleaseChangesUpstream() throws Exception {
        RouteConfig route = new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder()
                        .gray(new GrayConfig("percentage", "gray-backend:8080", 100.0, null, null))
                        .build());
        FilterContext ctx = newTestContext(route);
        ctx.claims(java.util.Map.of("sub", (Object) "user-1"));

        AtomicBoolean nextCalled = new AtomicBoolean(false);
        authFilter.apply(ctx, () -> nextCalled.set(true));
        assertTrue(nextCalled.get());

        GrayReleaseFilter grayFilter = new GrayReleaseFilter();
        AtomicBoolean nextCalled2 = new AtomicBoolean(false);
        grayFilter.apply(ctx, () -> nextCalled2.set(true));
        assertTrue(nextCalled2.get());
        assertEquals("gray-backend:8080", ctx.effectiveUpstream());
    }

    @Test
    void testFullChainHealthyFlow() throws Exception {
        String token = buildJwt("user-1", false);
        MockRequest req = new MockRequest();
        req.headers.put("Authorization", "Bearer " + token);
        req.method = HttpMethod.GET;
        MockResponse resp = new MockResponse();
        FilterContext ctx = new FilterContext(req, resp);

        RouteConfig route = new RouteConfig("r1", "/api/data", null, "backend:8080",
                FilterConfig.builder()
                        .auth(new AuthConfig("jwt", true, JWT_SECRET, null))
                        .rateLimit(new RateLimitConfig(100, 1000))
                        .circuitBreaker(new CircuitBreakerConfig(5, 5000))
                        .cache(new CacheConfig(true, 60))
                        .gray(new GrayConfig("percentage", "backend:8080", 0.0, null, null))
                        .build());
        ctx.route(route);

        FilterChain chain = new FilterChain(java.util.List.of(
                authFilter, rateLimitFilter, circuitBreakerFilter,
                new GrayReleaseFilter(), cacheFilter));

        AtomicBoolean proxyCalled = new AtomicBoolean(false);
        chain.execute(ctx, () -> proxyCalled.set(true));
        assertTrue(proxyCalled.get());
    }

    private FilterContext newTestContext(RouteConfig route) {
        MockRequest req = new MockRequest();
        MockResponse resp = new MockResponse();
        FilterContext ctx = new FilterContext(req, resp);
        ctx.route(route);
        return ctx;
    }

    private String buildJwt(String subject, boolean expired) throws Exception {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("test")
                .issueTime(now)
                .expirationTime(expired ? new Date(now.getTime() - 10000) : new Date(now.getTime() + 60000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(JWT_SECRET));
        return jwt.serialize();
    }
}
