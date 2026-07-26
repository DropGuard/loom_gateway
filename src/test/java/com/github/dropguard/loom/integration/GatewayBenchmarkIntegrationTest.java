package com.github.dropguard.loom.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.Date;

import org.junit.jupiter.api.Test;

/**
 * Loom half of the symmetric benchmark integration test. Asserts the same
 * features the SCG {@code GatewayScgIntegrationTest} asserts, so both sides of
 * the comparison are locked by CI: JWT auth, claims forwarding, benchmark
 * response headers, per-route timeout, and per-route rate limiting.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@QuarkusTestResource(MockBackendResource.class)
class GatewayBenchmarkIntegrationTest {

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    // --- JWT auth ---

    @Test
    void openRouteIsPublic() {
        given().get("/bench/open/data")
                .then()
                .statusCode(200);
    }

    @Test
    void authRouteRejectsMissingToken() {
        given().get("/bench/auth/data")
                .then()
                .statusCode(401);
    }

    @Test
    void authRouteAcceptsValidToken() throws Exception {
        given().header("Authorization", "Bearer " + signedToken("bench-user", "read"))
                .get("/bench/auth/data")
                .then()
                .statusCode(200);
    }

    // --- claims forwarding ---

    @Test
    void authRouteForwardsSubAsXUserIdToUpstream() throws Exception {
        given().header("Authorization", "Bearer " + signedToken("bench-user", "read"))
                .get("/bench/auth/data")
                .then()
                .statusCode(200)
                .body("x_user_id", equalTo("bench-user"))
                .body("x_claim_scope", equalTo("read"));
    }

    @Test
    void openRouteForwardsNoIdentityWhenAnonymous() {
        given().get("/bench/open/data")
                .then()
                .statusCode(200)
                .body("x_user_id", nullValue())
                .body("x_claim_scope", nullValue());
    }

    // --- benchmark response headers ---

    @Test
    void openRouteAddsBenchmarkResponseHeaders() {
        given().get("/bench/open/data")
                .then()
                .statusCode(200)
                .header("X-Gateway", "loom")
                .header("X-Request-Id", not(emptyOrNullString()))
                .header("X-Process-Time", not(emptyOrNullString()));
    }

    // --- per-route timeout ---

    @Test
    void slowUpstreamReturns504WithinTimeout() {
        // bench-slow routes to a MockBackend handler that delays 1500ms; the
        // gateway's 500ms per-route timeout must trip first and return 504.
        given().get("/bench/slow/data")
                .then()
                .statusCode(504);
    }

    // --- per-route rate limit ---

    @Test
    void rateLimitReturns429WhenExceeded() {
        // bench-ratelimit route is configured with limit 5 / 10s.
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            int status = given().get("/bench/rl/data").then().extract().statusCode();
            if (status == 429) {
                rejected++;
            } else if (status != 200) {
                throw new AssertionError("unexpected status " + status);
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(5, rejected,
                "fixed-window limiter must reject exactly the requests over the limit");
    }

    private static String signedToken(String sub, String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .claim("scope", scope)
                .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(JWT_SECRET));
        return jwt.serialize();
    }
}
