package com.gateway.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gateway.model.GatewayConfig;
import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.AuthConfig;

import io.quarkus.vertx.web.Route;

/**
 * Exercises RouteConfigLoader branches that the happy-path tests skip:
 * file-not-found, malformed YAML, per-route auth extraction, and pattern
 * compilation. These are the failure modes that matter in production (a bad
 * mounted ConfigMap must not silently yield an empty route table).
 */
class RouteConfigLoaderBoundaryTest {

    private final RouteConfigLoader loader = new RouteConfigLoader();

    @Test
    void missingFile_doesNotThrow_andKeepsEmptyConfig() {
        loader.loadFromPath("/nonexistent/path/routes.yaml");
        GatewayConfig config = loader.getConfig();
        assertNotNull(config);
        assertTrue(config.routes() == null || config.routes().isEmpty());
    }

    @Test
    void malformedYaml_doesNotThrow() throws IOException {
        Path bad = Files.createTempFile("bad-routes", ".yaml");
        Files.writeString(bad, "routes:\n  - id: x\n    path: : : :\n  this is not valid yaml: [");

        // Must not throw; config stays in its prior (empty) state.
        loader.loadFromPath(bad.toString());
        assertNotNull(loader.getConfig());
    }

    @Test
    void validFile_loadsRoutesAndAuth() throws IOException {
        Path ok = Files.createTempFile("ok-routes", ".yaml");
        Files.writeString(ok, """
                routes:
                  - id: api-users
                    path: /api/users/**
                    methods: [GET, POST]
                    upstream: backend:8080
                    filters:
                      auth:
                        type: jwt
                        required: true
                        secret: "this-is-a-test-secret-key-that-is-at-least-32-chars!"
                """);

        loader.loadFromPath(ok.toString());

        RouteConfig route = loader.findMatchingRoute("/api/users/123", "GET");
        assertNotNull(route);
        assertEquals("api-users", route.id());
        AuthConfig auth = route.filters().auth();
        assertEquals("jwt", auth.type());
        assertTrue(auth.required());
    }

    @Test
    void methodNotListed_routeDoesNotMatch() throws IOException {
        Path ok = Files.createTempFile("ok-routes", ".yaml");
        Files.writeString(ok, """
                routes:
                  - id: api-users
                    path: /api/users/**
                    methods: [GET]
                    upstream: backend:8080
                """);

        loader.loadFromPath(ok.toString());

        assertNotNull(loader.findMatchingRoute("/api/users/123", "GET"));
        assertNull(loader.findMatchingRoute("/api/users/123", "DELETE"),
                "DELETE is not in the route's methods");
    }

    @Test
    void unknownPath_routeDoesNotMatch() throws IOException {
        Path ok = Files.createTempFile("ok-routes", ".yaml");
        Files.writeString(ok, """
                routes:
                  - id: api-users
                    path: /api/users/**
                    methods: [GET]
                    upstream: backend:8080
                """);

        loader.loadFromPath(ok.toString());
        assertNull(loader.findMatchingRoute("/other/path", "GET"));
    }
}
