package com.github.dropguard.loom.integration;

import static org.hamcrest.Matchers.*;

import com.github.dropguard.loom.filter.CircuitBreakerRegistry;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@QuarkusTestResource(MockBackendResource.class)
class GatewayHttpIntegrationTest {

    @Inject
    CircuitBreakerRegistry circuitBreakerRegistry;

    // The dead-upstream route opens its circuit breaker, which is process-wide
    // state. Reset it after each test so a breaker left OPEN by one test does not
    // leak into another (e.g. the trace-id assertions on the same route). The
    // routeId is the config id `api-fail`, not the request path.
    @AfterEach
    void resetFailRouteBreaker() {
        circuitBreakerRegistry.reset("api-fail");
    }


    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    // ---- Route matching ----

    @Test
    void unmatchedRoute_returns404() {
        RestAssured.given()
                .get("/no/such/route")
                .then()
                .statusCode(404);
    }

    // ---- JWT Auth ----

    @Test
    void jwtRoute_noToken_returns401() {
        RestAssured.given()
                .get("/api/users/me")
                .then()
                .statusCode(401);
    }

    @Test
    void jwtRoute_invalidToken_returns401() {
        RestAssured.given()
                .header("Authorization", "Bearer invalid.token.here")
                .get("/api/users/me")
                .then()
                .statusCode(401);
    }

    @Test
    void jwtRoute_expiredToken_returns401() throws Exception {
        String token = buildJwt("user-1", true);
        RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("/api/users/me")
                .then()
                .statusCode(401);
    }

    @Test
    void jwtRoute_validToken_proxiesSuccessfully() throws Exception {
        String token = buildJwt("user-1", false);
        RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("/api/users/me")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("user", equalTo("test"))
                .body("path", equalTo("/api/users/me"));
    }

    @Test
    void jwtRoute_validToken_postAlsoWorks() throws Exception {
        String token = buildJwt("user-1", false);
        RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .post("/api/users/create")
                .then()
                .statusCode(200);
    }

    // ---- Public route (no auth) ----

    @Test
    void publicRoute_noAuth_returns200() {
        RestAssured.given()
                .get("/api/public/data")
                .then()
                .statusCode(200)
                .body("public", equalTo(true));
    }

    // ---- API Key Auth ----

    @Test
    void apiKeyRoute_noKey_returns401() {
        RestAssured.given()
                .get("/api/external/data")
                .then()
                .statusCode(401);
    }

    @Test
    void apiKeyRoute_wrongKey_returns401() {
        RestAssured.given()
                .header("X-API-Key", "wrong-key")
                .get("/api/external/data")
                .then()
                .statusCode(401);
    }

    @Test
    void apiKeyRoute_validKey_returns200() {
        RestAssured.given()
                .header("X-API-Key", "pk-test-key-123")
                .get("/api/external/data")
                .then()
                .statusCode(200)
                .body("external", equalTo(true));
    }

    // ---- Method filtering ----

    @Test
    void wrongMethod_returns404() {
        RestAssured.given()
                .delete("/api/public/data")
                .then()
                .statusCode(404);
    }

    // ---- Circuit breaker ----

    @Test
    void circuitBreaker_opensAfterFailures() {
        // Port 19998 has no listener → proxy fails with 502
        for (int i = 0; i < 3; i++) {
            RestAssured.given()
                    .get("/api/fail/test")
                    .then()
                    .statusCode(502);
        }

        // 4th: circuit breaker OPEN → 503
        RestAssured.given()
                .get("/api/fail/test")
                .then()
                .statusCode(503);
    }

    // ---- Query string forwarding ----

    @Test
    void queryString_isForwarded() throws Exception {
        String token = buildJwt("user-1", false);
        RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .queryParam("page", "1")
                .queryParam("size", "10")
                .get("/api/users/list")
                .then()
                .statusCode(200)
                .body("path", equalTo("/api/users/list"));
    }

    // ---- TraceId propagation (OpenTelemetry -> response header) ----

    @Test
    void unmatchedRoute_responseCarriesTraceId() {
        RestAssured.given()
                .get("/no/such/route")
                .then()
                .statusCode(404)
                .header("trace-id", matchesRegex("[0-9a-f]{32}"));
    }

    @Test
    void successfulRoute_responseCarriesTraceId() {
        RestAssured.given()
                .get("/api/public/data")
                .then()
                .statusCode(200)
                .header("trace-id", matchesRegex("[0-9a-f]{32}"));
    }

    @Test
    void upstreamFailure_responseCarriesTraceId() {
        // /api/fail/test hits a dead upstream → 502. The breaker is reset in
        // @AfterEach so this stays deterministic regardless of test order.
        RestAssured.given()
                .get("/api/fail/test")
                .then()
                .statusCode(502)
                .header("trace-id", matchesRegex("[0-9a-f]{32}"));
    }

    // ---- Health checks ----

    @Test
    void liveness_alwaysUp() {
        RestAssured.given()
                .get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    // ---- Metrics ----

    @Test
    void metrics_prometheusEndpointAvailable() {
        RestAssured.given()
                .get("/q/metrics")
                .then()
                .statusCode(200)
                .contentType(containsString("openmetrics-text"));
    }

    @Test
    void readiness_upWhenRoutesLoaded() {
        RestAssured.given()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'gateway-ready' }.data.routes", greaterThan(0));
    }

    // ---- X-User-Id injection ----

    @Test
    void jwtRoute_injectsUserIdHeader() throws Exception {
        String token = buildJwt("user-42", false);
        RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("/api/users/me")
                .then()
                .statusCode(200)
                .body("userId", equalTo("user-42"));
    }

    @Test
    void jwtRoute_stripsForgedUserIdHeader() throws Exception {
        String token = buildJwt("real-user", false);
        RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "forged-admin")
                .get("/api/users/me")
                .then()
                .statusCode(200)
                .body("userId", equalTo("real-user"));
    }

    // ---- Rate limiting ----

    @Test
    void rateLimit_blocksAfterExceeding() {
        for (int i = 0; i < 3; i++) {
            RestAssured.given()
                    .get("/api/limited/data")
                    .then()
                    .statusCode(200);
        }

        RestAssured.given()
                .get("/api/limited/data")
                .then()
                .statusCode(429);
    }

    // ---- CORS ----

    @Test
    void cors_simpleRequest_returnsAllowOrigin() {
        RestAssured.given()
                .header("Origin", "http://example.com")
                .get("/api/public/data")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "http://example.com");
    }

    @Test
    void cors_preflight_returnsCorrectHeaders() {
        RestAssured.given()
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization")
                .options("/api/users/me")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "http://example.com")
                .header("Access-Control-Allow-Methods", containsString("POST"))
                .header("Access-Control-Allow-Headers", containsString("Authorization"));
    }

    // ---- Helper ----

    private String buildJwt(String subject, boolean expired) throws Exception {
        java.util.Date now = new java.util.Date();
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("test")
                .issueTime(now)
                .expirationTime(expired
                        ? new java.util.Date(now.getTime() - 10000)
                        : new java.util.Date(now.getTime() + 60000))
                .build();

        var jwt = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(JWT_SECRET));
        return jwt.serialize();
    }
}
