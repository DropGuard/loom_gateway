package com.github.dropguard.loom.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocketConnectOptions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@QuarkusTestResource(MockBackendResource.class)
class GatewayWebSocketCircuitBreakerTest {

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    private static Vertx vertx;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
        if (vertx != null) vertx.close();
    }

    /**
     * The core regression: a WebSocket upgrade completes asynchronously, so under the old model
     * every successful upgrade was counted as a failure and the breaker opened after threshold
     * successes. Here, repeated successes must keep the breaker CLOSED (ws-chat has
     * failureThreshold=3).
     */
    @Test
    void successfulUpgrades_doNotTripBreaker() throws Exception {
        for (int i = 0; i < 5; i++) {
            String token = buildJwt("ws-user-" + i, false);
            WebSocketConnectOptions opts = wsOpts("/ws/chat/room" + i);
            opts.addHeader("Authorization", "Bearer " + token);

            var result = new CompletableFuture<String>();
            client.webSocket(opts)
                    .onSuccess(
                            ws -> {
                                ws.textMessageHandler(result::complete);
                                ws.writeTextMessage("hi");
                            })
                    .onFailure(result::completeExceptionally);

            // Every upgrade must succeed, even after several prior successes.
            assertEquals(
                    "echo:hi",
                    result.get(5, TimeUnit.SECONDS),
                    "WS upgrade #" + (i + 1) + " should succeed (breaker must stay closed)");
        }
    }

    /**
     * Repeated WS upgrade failures must still open the breaker. ws-fail points at a port with no
     * listener, so every upgrade fails.
     */
    @Test
    void failures_openBreaker() throws Exception {
        for (int i = 0; i < 3; i++) {
            var failed = new CompletableFuture<Boolean>();
            client.webSocket(wsOpts("/ws/fail/" + i))
                    .onSuccess(
                            ws -> {
                                // Gateway upgrades the client handshake, then fails to reach the
                                // (unlistened) upstream and closes the client socket — that close
                                // is the signal the upgrade ultimately failed.
                                ws.exceptionHandler(err -> failed.complete(true));
                                ws.closeHandler(v -> failed.complete(true));
                            })
                    .onFailure(err -> failed.complete(true));
            assertTrue(failed.get(5, TimeUnit.SECONDS), "WS failure #" + (i + 1) + " expected");
        }

        // 4th attempt: breaker is OPEN -> connection refused / fails fast.
        var blocked = new CompletableFuture<Boolean>();
        client.webSocket(wsOpts("/ws/fail/blocked"))
                .onSuccess(
                        ws -> {
                            ws.exceptionHandler(err -> blocked.complete(true));
                            ws.closeHandler(v -> blocked.complete(true));
                        })
                .onFailure(err -> blocked.complete(true));
        assertTrue(blocked.get(5, TimeUnit.SECONDS), "Breaker should be OPEN after 3 failures");
    }

    private WebSocketConnectOptions wsOpts(String path) {
        return new WebSocketConnectOptions().setHost("localhost").setPort(8081).setURI(path);
    }

    private String buildJwt(String subject, boolean expired) throws Exception {
        java.util.Date now = new java.util.Date();
        var claims =
                new com.nimbusds.jwt.JWTClaimsSet.Builder()
                        .subject(subject)
                        .issuer("test")
                        .issueTime(now)
                        .expirationTime(
                                expired
                                        ? new java.util.Date(now.getTime() - 10000)
                                        : new java.util.Date(now.getTime() + 60000))
                        .build();

        var jwt =
                new com.nimbusds.jwt.SignedJWT(
                        new com.nimbusds.jose.JWSHeader.Builder(
                                        com.nimbusds.jose.JWSAlgorithm.HS256)
                                .build(),
                        claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(JWT_SECRET));
        return jwt.serialize();
    }
}
