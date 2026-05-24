package com.gateway.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;

import java.util.Map;

public class MockBackendResource implements QuarkusTestResourceLifecycleManager {

    private Vertx vertx;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer()
                .webSocketHandler(ws -> {
                    if (ws.path().startsWith("/ws/")) {
                        ws.textMessageHandler(msg -> ws.writeTextMessage("echo:" + msg));
                        ws.binaryMessageHandler(buf -> ws.writeBinaryMessage(buf));
                    } else {
                        ws.reject(404);
                    }
                })
                .requestHandler(req -> {
                    String path = req.path();
                    if ("/health".equals(path)) {
                        req.response().setStatusCode(200).end("OK");
                    } else if (path.startsWith("/api/users")) {
                        JsonObject json = new JsonObject()
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
                        req.bodyHandler(body -> {
                            int len = body != null ? body.length() : 0;
                            json(req, new JsonObject().put("receivedBytes", len));
                        });
                    } else if (path.startsWith("/api/limited")) {
                        json(req, new JsonObject().put("limited", true));
                    } else if (path.startsWith("/api/fail")) {
                        req.response().setStatusCode(500).end("Internal Server Error");
                    } else {
                        req.response().setStatusCode(404).end("Not Found");
                    }
                })
                .listen(19999)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        System.out.println("[MockBackend] Started on port 19999");
        return Map.of();
    }

    private static void json(io.vertx.core.http.HttpServerRequest req, JsonObject body) {
        req.response()
                .putHeader("Content-Type", "application/json")
                .end(body.encode());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().join();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }
        System.out.println("[MockBackend] Stopped");
    }
}
