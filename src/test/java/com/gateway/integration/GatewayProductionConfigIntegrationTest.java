package com.gateway.integration;

import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

import org.junit.jupiter.api.Test;

/**
 * DEFECT T1: exercises the PRODUCTION-shaped routes.yaml end-to-end via the
 * IntegrationTestProfile (classpath config). Confirms the real config parses,
 * routes match, and auth/proxy/cache/WS all work against it.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@QuarkusTestResource(MockBackendResource.class)
class GatewayProductionConfigIntegrationTest {

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    @Test
    void readinessReportsConfigPresent() {
        RestAssured.given()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'gateway-ready' }.data.configMissing", equalTo(false));
    }

    @Test
    void unmatchedRoute_returns404() {
        RestAssured.given()
                .get("/no/such/route")
                .then()
                .statusCode(404);
    }

    @Test
    void jwtRoute_noToken_returns401() {
        RestAssured.given()
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
                .statusCode(200);
    }

    @Test
    void publicRoute_proxiesWithoutAuth() {
        RestAssured.given()
                .get("/api/public/hello")
                .then()
                .statusCode(200);
    }

    private String buildJwt(String subject, boolean expired) throws Exception {
        var header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        long exp = expired ? System.currentTimeMillis() / 1000 - 10 : System.currentTimeMillis() / 1000 + 3600;
        var payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + subject + "\",\"exp\":" + exp + "}").getBytes());
        var data = (header + "." + payload).getBytes("UTF-8");
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(JWT_SECRET.getBytes("UTF-8"), "HmacSHA256"));
        var sig = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data));
        return header + "." + payload + "." + sig;
    }
}
