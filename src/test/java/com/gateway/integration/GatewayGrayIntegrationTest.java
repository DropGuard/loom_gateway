package com.gateway.integration;

import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@QuarkusTestResource(MockBackendResource.class)
class GatewayGrayIntegrationTest {

    private static final String GRAY_KEY = "pk-gray-key";

    // ---- mode=percentage: routes a deterministic fraction to the canary ----

    @Test
    void percentageGray_routesToCanary() {
        RestAssured.given()
                .header("X-API-Key", GRAY_KEY)
                .get("/api/gray/me")
                .then()
                .statusCode(200)
                .body("canary", equalTo(true));
    }

    // ---- Client-supplied gray header is stripped at ingress ----

    @Test
    void percentageGray_stripsClientCanaryHeader_beforeForwarding() {
        RestAssured.given()
                .header("X-API-Key", GRAY_KEY)
                .header("X-Canary", "true")
                .get("/api/gray/me")
                .then()
                .statusCode(200)
                .body("canary", equalTo(true))
                // The canary backend echoes headers it actually received; the
                // forged X-Canary must NOT have reached it.
                .body("receivedCanaryHeader", nullValue());
    }

    // ---- mode=rule: a trusted layer's dedicated header drives canary routing ----

    @Test
    void ruleGray_trustedHeader_routesToCanary() {
        RestAssured.given()
                .header("X-API-Key", GRAY_KEY)
                .header("X-Internal-Canary", "true")
                .get("/api/gray-rule/me")
                .then()
                .statusCode(200)
                .body("canary", equalTo(true))
                .body("receivedTrustedHeader", equalTo("true"));
    }

    @Test
    void ruleGray_withoutTrustedHeader_staysOnPrimary() {
        RestAssured.given()
                .header("X-API-Key", GRAY_KEY)
                .get("/api/gray-rule/me")
                .then()
                .statusCode(200)
                .body("canary", equalTo(false));
    }

    // ---- A forged client header alone (no valid auth) cannot drive canary ----

    @Test
    void gray_withoutAuth_returns401_notCanary() {
        RestAssured.given()
                .header("X-Canary", "true")
                .get("/api/gray/me")
                .then()
                .statusCode(401);
    }
}
