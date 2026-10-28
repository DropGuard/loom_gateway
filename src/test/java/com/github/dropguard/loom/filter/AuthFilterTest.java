package com.github.dropguard.loom.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.model.RouteConfig;
import com.github.dropguard.loom.model.RouteConfig.AuthConfig;
import com.github.dropguard.loom.model.RouteConfig.FilterConfig;
import com.github.dropguard.loom.testutil.MockRequest;
import com.github.dropguard.loom.testutil.MockResponse;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthFilterTest {

    private AuthFilter filter;
    private MockRequest request;
    private MockResponse response;
    private FilterContext context;

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    @BeforeEach
    void setUp() {
        filter = new AuthFilter();
        request = new MockRequest();
        response = new MockResponse();
        context = new FilterContext(request, response);
    }

    private boolean applyAndCheckNext(FilterContext ctx) throws Exception {
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        filter.apply(ctx, () -> nextCalled.set(true));
        return nextCalled.get();
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

    private RouteConfig routeWithAuth(AuthConfig auth) {
        return new RouteConfig("r1", "/**", null, "", FilterConfig.builder().auth(auth).build());
    }

    @Test
    void authNotRequired_passesThrough() throws Exception {
        context.route(routeWithAuth(new AuthConfig("jwt", false, null, null)));
        assertTrue(applyAndCheckNext(context));
    }

    @Test
    void validJwt_passesAndPopulatesClaims() throws Exception {
        String token = buildJwt("user-1", false);
        request.headers.put("Authorization", "Bearer " + token);
        context.route(routeWithAuth(new AuthConfig("jwt", true, JWT_SECRET, null)));

        assertTrue(applyAndCheckNext(context));
        assertNotNull(context.claims());
    }

    @Test
    void missingAuthorizationHeader_returns401() throws Exception {
        context.route(routeWithAuth(new AuthConfig("jwt", true, JWT_SECRET, null)));

        assertFalse(applyAndCheckNext(context));
        assertEquals(401, response.statusCode);
    }

    @Test
    void expiredJwt_returns401() throws Exception {
        String token = buildJwt("user-1", true);
        request.headers.put("Authorization", "Bearer " + token);
        context.route(routeWithAuth(new AuthConfig("jwt", true, JWT_SECRET, null)));

        assertFalse(applyAndCheckNext(context));
        assertEquals(401, response.statusCode);
    }

    @Test
    void validApiKey_passes() throws Exception {
        request.headers.put("X-API-Key", "valid-key-123");
        context.route(
                routeWithAuth(
                        new AuthConfig(
                                "api-key", true, null, List.of("valid-key-123", "valid-key-456"))));
        assertTrue(applyAndCheckNext(context));
    }

    @Test
    void missingApiKey_returns401() throws Exception {
        context.route(
                routeWithAuth(new AuthConfig("api-key", true, null, List.of("valid-key-123"))));

        assertFalse(applyAndCheckNext(context));
        assertEquals(401, response.statusCode);
    }

    @Test
    void invalidApiKey_returns401() throws Exception {
        request.headers.put("X-API-Key", "wrong-key");
        context.route(
                routeWithAuth(new AuthConfig("api-key", true, null, List.of("valid-key-123"))));

        assertFalse(applyAndCheckNext(context));
        assertEquals(401, response.statusCode);
    }

    // ---- Boundary tests ----

    @Test
    void jwtSecretMissing_returns500() throws Exception {
        for (String secret : new String[] {null, ""}) {
            MockRequest req = new MockRequest();
            req.headers.put("Authorization", "Bearer " + buildJwt("user-1", false));
            MockResponse resp = new MockResponse();
            FilterContext ctx = new FilterContext(req, resp);
            ctx.route(routeWithAuth(new AuthConfig("jwt", true, secret, null)));

            assertFalse(applyAndCheckNext(ctx), "secret=" + secret);
            assertEquals(500, resp.statusCode, "secret=" + secret);
        }
    }

    @Test
    void jwtSignedWithDifferentSecret_returns401() throws Exception {
        // Sign with a different secret
        Date now = new Date();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .subject("user-1")
                        .issuer("test")
                        .issueTime(now)
                        .expirationTime(new Date(now.getTime() + 60000))
                        .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner("wrong-secret-key-that-is-at-least-32-chars!!"));
        String token = jwt.serialize();

        request.headers.put("Authorization", "Bearer " + token);
        context.route(routeWithAuth(new AuthConfig("jwt", true, JWT_SECRET, null)));

        assertFalse(applyAndCheckNext(context));
        assertEquals(401, response.statusCode);
    }

    @Test
    void malformedJwt_returns401() throws Exception {
        request.headers.put("Authorization", "Bearer not-a-valid-jwt");
        context.route(routeWithAuth(new AuthConfig("jwt", true, JWT_SECRET, null)));

        assertFalse(applyAndCheckNext(context));
        assertEquals(401, response.statusCode);
    }

    @Test
    void jwtNotYetValid_returns401() throws Exception {
        Date now = new Date();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .subject("user-1")
                        .issuer("test")
                        .issueTime(now)
                        .notBeforeTime(new Date(now.getTime() + 60000)) // 1 minute in the future
                        .expirationTime(new Date(now.getTime() + 120000))
                        .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(JWT_SECRET));
        String token = jwt.serialize();

        request.headers.put("Authorization", "Bearer " + token);
        context.route(routeWithAuth(new AuthConfig("jwt", true, JWT_SECRET, null)));

        assertFalse(applyAndCheckNext(context));
        assertEquals(401, response.statusCode);
    }

    @Test
    void unknownAuthType_returns403() throws Exception {
        context.route(routeWithAuth(new AuthConfig("oauth2", true, null, null)));

        assertFalse(applyAndCheckNext(context));
        assertEquals(403, response.statusCode);
    }
}
