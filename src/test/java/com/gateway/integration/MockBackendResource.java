package com.gateway.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

import java.util.Map;

/**
 * Starts a mock HTTP backend for integration tests.
 * Managed by Quarkus test lifecycle — starts before the app, stops after.
 */
public class MockBackendResource implements QuarkusTestResourceLifecycleManager {

    private Vertx vertx;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer()
                .requestHandler(req -> {
                    String path = req.path();
                    if ("/health".equals(path)) {
                        req.response().setStatusCode(200).end("OK");
                    } else if (path.startsWith("/api/users")) {
                        String auth = req.getHeader("Authorization");
                        if (auth == null || !auth.startsWith("Bearer ")) {
                            req.response().setStatusCode(401).end("Unauthorized");
                            return;
                        }
                        req.response()
                                .putHeader("Content-Type", "application/json")
                                .end("{\"user\":\"test\",\"path\":\"" + path + "\"}");
                    } else if (path.startsWith("/api/public")) {
                        req.response()
                                .putHeader("Content-Type", "application/json")
                                .end("{\"public\":true}");
                    } else if (path.startsWith("/api/external")) {
                        req.response()
                                .putHeader("Content-Type", "application/json")
                                .end("{\"external\":true}");
                    } else if (path.startsWith("/api/echo")) {
                        req.bodyHandler(body -> {
                            int len = body != null ? body.length() : 0;
                            req.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end("{\"receivedBytes\":" + len + "}");
                        });
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
        return Map.of(); // no extra config needed
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
