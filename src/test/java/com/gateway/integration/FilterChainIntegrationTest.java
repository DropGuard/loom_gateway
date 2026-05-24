package com.gateway.integration;

import com.gateway.filter.*;
import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.AuthConfig;
import com.gateway.model.RouteConfig.CacheConfig;
import com.gateway.model.RouteConfig.CircuitBreakerConfig;
import com.gateway.model.RouteConfig.FilterConfig;
import com.gateway.model.RouteConfig.GrayConfig;
import com.gateway.model.RouteConfig.RateLimitConfig;
import com.gateway.testutil.MockRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

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
        circuitBreakerFilter = new CircuitBreakerFilter();
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

        FilterResult authResult = authFilter.filter(ctx);
        assertFalse(authResult.continueChain());
        assertEquals(401, authResult.statusCode());

        FilterContext ctx2 = newTestContext(route);
        assertFalse(authFilter.filter(ctx2).continueChain());
    }

    @Test
    void testCacheFilterSkipsNonGetRequests() {
        MockRequest req = new MockRequest();
        req.method = HttpMethod.POST;
        FilterContext ctx = new FilterContext(req, null);

        RouteConfig route = new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder().cache(new CacheConfig(true, 60)).build());
        ctx.route(route);

        assertTrue(cacheFilter.filter(ctx).continueChain());
    }

    @Test
    void testCircuitBreakerBlocksAfterFailures() {
        RouteConfig route = new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder().circuitBreaker(new CircuitBreakerConfig(3, 5000)).build());
        FilterContext ctx = newTestContext(route);

        assertTrue(circuitBreakerFilter.filter(ctx).continueChain());

        circuitBreakerFilter.recordOutcome("r1", false);
        circuitBreakerFilter.recordOutcome("r1", false);
        circuitBreakerFilter.recordOutcome("r1", false);

        FilterResult result = circuitBreakerFilter.filter(ctx);
        assertFalse(result.continueChain());
        assertEquals(503, result.statusCode());
    }

    @Test
    void testGrayReleaseChangesUpstream() {
        RouteConfig route = new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder()
                        .gray(new GrayConfig(100, "gray-backend:8080", null))
                        .build());
        FilterContext ctx = newTestContext(route);
        ctx.claims(java.util.Map.of("sub", (Object) "user-1"));

        assertTrue(authFilter.filter(ctx).continueChain());

        GrayReleaseFilter grayFilter = new GrayReleaseFilter();
        assertTrue(grayFilter.filter(ctx).continueChain());
        assertEquals("gray-backend:8080", ctx.effectiveUpstream());
    }

    @Test
    void testFullChainHealthyFlow() throws Exception {
        String token = buildJwt("user-1", false);
        MockRequest req = new MockRequest();
        req.headers.put("Authorization", "Bearer " + token);
        req.method = HttpMethod.GET;
        FilterContext ctx = new FilterContext(req, null);

        RouteConfig route = new RouteConfig("r1", "/api/data", null, "backend:8080",
                FilterConfig.builder()
                        .auth(new AuthConfig("jwt", true, JWT_SECRET, null))
                        .rateLimit(new RateLimitConfig(100, 1000))
                        .circuitBreaker(new CircuitBreakerConfig(5, 5000))
                        .cache(new CacheConfig(true, 60))
                        .gray(new GrayConfig(0, null, null))
                        .build());
        ctx.route(route);

        assertTrue(authFilter.filter(ctx).continueChain());
        assertTrue(rateLimitFilter.filter(ctx).continueChain());
        assertTrue(circuitBreakerFilter.filter(ctx).continueChain());
        assertTrue(cacheFilter.filter(ctx).continueChain());
    }

    private FilterContext newTestContext(RouteConfig route) {
        MockRequest req = new MockRequest();
        FilterContext ctx = new FilterContext(req, null);
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
