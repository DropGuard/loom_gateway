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
class GatewayWebSocketIntegrationTest {

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

    @Test
    void wsPublicRoute_echoText() throws Exception {
        var result = new CompletableFuture<String>();

        client.webSocket(wsOpts("/ws/public/echo"))
                .onSuccess(
                        ws -> {
                            ws.textMessageHandler(result::complete);
                            ws.writeTextMessage("hello");
                        })
                .onFailure(result::completeExceptionally);

        assertEquals("echo:hello", result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void wsPublicRoute_echoBinary() throws Exception {
        var result = new CompletableFuture<byte[]>();
        byte[] payload = new byte[] {1, 2, 3, 4, 5};

        client.webSocket(wsOpts("/ws/public/echo"))
                .onSuccess(
                        ws -> {
                            ws.binaryMessageHandler(buf -> result.complete(buf.getBytes()));
                            ws.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(payload));
                        })
                .onFailure(result::completeExceptionally);

        assertArrayEquals(payload, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void wsJwtRoute_noToken_rejected() throws Exception {
        var result = new CompletableFuture<Integer>();

        client.webSocket(wsOpts("/ws/chat/room1"))
                .onSuccess(
                        ws -> {
                            result.complete(-1);
                            ws.close();
                        })
                .onFailure(err -> result.complete(401));

        // The connection should fail because auth filter rejects before upgrade
        // The gateway responds with 401, which causes the WebSocket handshake to fail
        int status = result.get(5, TimeUnit.SECONDS);
        assertEquals(401, status);
    }

    @Test
    void wsJwtRoute_validToken_echoWorks() throws Exception {
        String token = buildJwt("ws-user-1", false);
        var result = new CompletableFuture<String>();

        WebSocketConnectOptions opts = wsOpts("/ws/chat/room1");
        opts.addHeader("Authorization", "Bearer " + token);

        client.webSocket(opts)
                .onSuccess(
                        ws -> {
                            ws.textMessageHandler(result::complete);
                            ws.writeTextMessage("authenticated");
                        })
                .onFailure(result::completeExceptionally);

        assertEquals("echo:authenticated", result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void wsClientClose_cleanDisconnect() throws Exception {
        var closed = new CompletableFuture<Void>();

        client.webSocket(wsOpts("/ws/public/echo"))
                .onSuccess(
                        ws -> {
                            ws.closeHandler(v -> closed.complete(null));
                            ws.close();
                        })
                .onFailure(closed::completeExceptionally);

        assertDoesNotThrow(() -> closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void wsUnmatchedRoute_rejected() throws Exception {
        var result = new CompletableFuture<Boolean>();

        client.webSocket(wsOpts("/no/such/ws/route"))
                .onSuccess(
                        ws -> {
                            result.complete(false);
                            ws.close();
                        })
                .onFailure(err -> result.complete(true));

        assertTrue(result.get(5, TimeUnit.SECONDS));
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
