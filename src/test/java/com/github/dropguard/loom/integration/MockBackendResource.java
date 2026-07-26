package com.github.dropguard.loom.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;

import java.util.Map;

public class MockBackendResource implements QuarkusTestResourceLifecycleManager {

    private Vertx vertx;
    private HttpServer server;
    private HttpServer canaryServer;

    @Override
    public Map<String, String> start() {
        vertx = Vertx.vertx();
        server =
                vertx.createHttpServer()
                        .webSocketHandler(
                                ws -> {
                                    if (ws.path().startsWith("/ws/")) {
                                        ws.textMessageHandler(
                                                msg -> ws.writeTextMessage("echo:" + msg));
                                        ws.binaryMessageHandler(buf -> ws.writeBinaryMessage(buf));
                                    } else {
                                        ws.reject(404);
                                    }
                                })
                        .requestHandler(
                                req -> {
                                    String path = req.path();
                                    if ("/health".equals(path)) {
                                        req.response().setStatusCode(200).end("OK");
                                    } else if (path.startsWith("/api/users")) {
                                        JsonObject json =
                                                new JsonObject()
                                                        .put("user", "test")
                                                        .put("path", path);
                                        String userId = req.getHeader("X-User-Id");
                                        if (userId != null) json.put("userId", userId);
                                        json(req, json);
                                    } else if (path.startsWith("/api/public")) {
                                        json(req, new JsonObject().put("public", true));
                                    } else if (path.startsWith("/api/external")) {
                                        json(req, new JsonObject().put("external", true));
                                    } else if (path.startsWith("/api/echo")) {
                                        req.bodyHandler(
                                                body -> {
                                                    int len = body != null ? body.length() : 0;
                                                    json(
                                                            req,
                                                            new JsonObject()
                                                                    .put("receivedBytes", len));
                                                });
                                    } else if (path.startsWith("/api/limited")) {
                                        json(req, new JsonObject().put("limited", true));
                                    } else if (path.startsWith("/api/fail")) {
                                        req.response()
                                                .setStatusCode(500)
                                                .end("Internal Server Error");
                                    } else if (path.startsWith("/api/gray")) {
                                        // Primary backend for gray routes: reports canary=false and
                                        // echoes whether the client-supplied X-Canary header
                                        // reached it.
                                        JsonObject json =
                                                new JsonObject()
                                                        .put("canary", false)
                                                        .put(
                                                                "receivedCanaryHeader",
                                                                req.getHeader("X-Canary"));
                                        json(req, json);
                                    } else if (path.startsWith("/bench/slow")) {
                                        // Benchmark timeout route: respond after 1500ms so the
                                        // gateway's 500ms per-route timeout trips first (504).
                                        req.response()
                                                .putHeader("Content-Type", "application/json");
                                        vertx.setTimer(
                                                1500,
                                                id ->
                                                        req.response()
                                                                .setStatusCode(200)
                                                                .end(
                                                                        new JsonObject()
                                                                                .put("slow", true)
                                                                                .encode()));
                                    } else if (path.startsWith("/bench/")) {
                                        // Benchmark routes: echo the downstream headers the gateway
                                        // forwarded (e.g. X-User-Id, X-Claim-scope) so integration
                                        // tests can assert claims-forwarding reached the upstream.
                                        JsonObject json =
                                                new JsonObject()
                                                        .put(
                                                                "x_user_id",
                                                                req.getHeader("X-User-Id"))
                                                        .put(
                                                                "x_claim_scope",
                                                                req.getHeader("X-Claim-scope"));
                                        json(req, json);
                                    } else {
                                        req.response().setStatusCode(404).end("Not Found");
                                    }
                                })
                        .listen(19999)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join();

        // Canary backend: proves a request was actually routed to the gray upstream.
        canaryServer =
                vertx.createHttpServer()
                        .requestHandler(
                                req -> {
                                    if (req.path().startsWith("/api/gray")) {
                                        JsonObject json =
                                                new JsonObject()
                                                        .put("canary", true)
                                                        .put(
                                                                "receivedCanaryHeader",
                                                                req.getHeader("X-Canary"))
                                                        .put(
                                                                "receivedTrustedHeader",
                                                                req.getHeader("X-Internal-Canary"));
                                        json(req, json);
                                    } else {
                                        req.response().setStatusCode(404).end("Not Found");
                                    }
                                })
                        .listen(19997)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join();

        System.out.println("[MockBackend] Started on port 19999 (primary) and 19997 (canary)");
        return Map.of();
    }

    private static void json(io.vertx.core.http.HttpServerRequest req, JsonObject body) {
        req.response().putHeader("Content-Type", "application/json").end(body.encode());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().join();
        }
        if (canaryServer != null) {
            canaryServer.close().toCompletionStage().toCompletableFuture().join();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }
        System.out.println("[MockBackend] Stopped");
    }
}
