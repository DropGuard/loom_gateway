package com.gateway.filter;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.AuthConfig;
import com.gateway.testutil.MockRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthFilterTest {

    private AuthFilter filter;
    private MockRequest request;
    private FilterContext context;

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    @BeforeEach
    void setUp() {
        filter = new AuthFilter();
        request = new MockRequest();
        context = new FilterContext(request, null);
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
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                claims
        );
        jwt.sign(new MACSigner(JWT_SECRET));
        return jwt.serialize();
    }

    @Test
    void testNoAuthRequiredPasses() {
        AuthConfig auth = new AuthConfig("jwt", false, null, null);
        context.route(new RouteConfig("r1", "/**", null, "", auth, null, null, null, null));

        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testJwtValidToken() throws Exception {
        String token = buildJwt("user-1", false);
        request.headers.put("Authorization", "Bearer " + token);

        AuthConfig auth = new AuthConfig("jwt", true, JWT_SECRET, null);
        context.route(new RouteConfig("r1", "/**", null, "", auth, null, null, null, null));

        FilterResult result = filter.filter(context);
        assertTrue(result.continueChain());
        assertNotNull(context.getAttribute("claims"));
    }

    @Test
    void testJwtMissingHeader() {
        AuthConfig auth = new AuthConfig("jwt", true, JWT_SECRET, null);
        context.route(new RouteConfig("r1", "/**", null, "", auth, null, null, null, null));

        FilterResult result = filter.filter(context);
        assertFalse(result.continueChain());
        assertEquals(401, result.statusCode());
    }

    @Test
    void testJwtExpiredToken() throws Exception {
        String token = buildJwt("user-1", true);
        request.headers.put("Authorization", "Bearer " + token);

        AuthConfig auth = new AuthConfig("jwt", true, JWT_SECRET, null);
        context.route(new RouteConfig("r1", "/**", null, "", auth, null, null, null, null));

        FilterResult result = filter.filter(context);
        assertFalse(result.continueChain());
        assertEquals(401, result.statusCode());
    }

    @Test
    void testApiKeyValid() {
        request.headers.put("X-API-Key", "valid-key-123");

        AuthConfig auth = new AuthConfig("api-key", true, null, List.of("valid-key-123", "valid-key-456"));
        context.route(new RouteConfig("r1", "/**", null, "", auth, null, null, null, null));

        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testApiKeyMissing() {
        AuthConfig auth = new AuthConfig("api-key", true, null, List.of("valid-key-123"));
        context.route(new RouteConfig("r1", "/**", null, "", auth, null, null, null, null));

        FilterResult result = filter.filter(context);
        assertFalse(result.continueChain());
        assertEquals(401, result.statusCode());
    }

    @Test
    void testApiKeyInvalid() {
        request.headers.put("X-API-Key", "wrong-key");

        AuthConfig auth = new AuthConfig("api-key", true, null, List.of("valid-key-123"));
        context.route(new RouteConfig("r1", "/**", null, "", auth, null, null, null, null));

        FilterResult result = filter.filter(context);
        assertFalse(result.continueChain());
        assertEquals(401, result.statusCode());
    }
}
