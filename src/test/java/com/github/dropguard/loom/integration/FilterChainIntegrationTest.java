package com.github.dropguard.loom.filter;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Verifies the composition contract: every filter running in priority order passes a healthy
 * authenticated request through to the proxy.
 */
class FilterChainIntegrationTest {

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    @Test
    void fullChain_healthyRequestReachesProxy() throws Exception {
        MockRequest req = new MockRequest();
        req.headers.put("Authorization", "Bearer " + buildJwt("user-1", false));
        req.method = HttpMethod.GET;
        FilterContext ctx = new FilterContext(req, new MockResponse());

        RouteConfig route =
                new RouteConfig(
                        "r1",
                        "/api/data",
                        null,
                        "backend:8080",
                        FilterConfig.builder()
                                .auth(new AuthConfig("jwt", true, JWT_SECRET, null))
                                .rateLimit(new RateLimitConfig(100, 1000))
                                .circuitBreaker(new CircuitBreakerConfig(5, 5000))
                                .cache(new CacheConfig(true, 60))
                                .gray(new GrayConfig("percentage", "backend:8080", 0.0, null, null))
                                .build());
        ctx.route(route);

        FilterChain chain =
                new FilterChain(
                        List.of(
                                new AuthFilter(),
                                new RateLimitFilter(),
                                new CircuitBreakerFilter(
                                        new CircuitBreakerRegistry(),
                                        new GatewayMetrics(new SimpleMeterRegistry())),
                                new GrayReleaseFilter(),
                                new CacheFilter()));

        AtomicBoolean proxyCalled = new AtomicBoolean(false);
        chain.execute(ctx, () -> proxyCalled.set(true));

        assertTrue(proxyCalled.get());
    }

    private String buildJwt(String subject, boolean expired) throws Exception {
        Date now = new Date();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issuer("test")
                        .issueTime(now)
                        .expirationTime(
                                expired
                                        ? new Date(now.getTime() - 10000)
                                        : new Date(now.getTime() + 60000))
                        .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(JWT_SECRET));
        return jwt.serialize();
    }
}
