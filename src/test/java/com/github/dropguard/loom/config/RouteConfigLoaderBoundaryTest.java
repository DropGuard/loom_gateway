package com.github.dropguard.loom.config;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.exception.ConfigException;
import com.github.dropguard.loom.model.GatewayConfig;
import com.github.dropguard.loom.model.RouteConfig;
import com.github.dropguard.loom.model.RouteConfig.AuthConfig;
import com.github.dropguard.loom.model.RouteConfig.FilterConfig;
import com.github.dropguard.loom.model.RouteConfig.RateLimitConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercises RouteConfigLoader branches that the happy-path tests skip: file-not-found, malformed
 * YAML, per-route auth extraction, validation, and reload semantics. These are the failure modes
 * that matter in production (a bad mounted ConfigMap must not silently yield an empty route table).
 */
class RouteConfigLoaderBoundaryTest {

    private final RouteConfigLoader loader = new RouteConfigLoader();

    @Test
    void missingFile_failsFast() {
        assertThrows(
                ConfigException.class, () -> loader.loadFromPath("/nonexistent/path/routes.yaml"));

        GatewayConfig config = loader.getConfig();
        assertNotNull(config);
        assertTrue(config.routes() == null || config.routes().isEmpty());
    }

    @Test
    void malformedYaml_failsFast() throws IOException {
        Path bad = Files.createTempFile("bad-routes", ".yaml");
        Files.writeString(bad, "routes:\n  - id: x\n    path: : : :\n  this is not valid yaml: [");

        assertThrows(ConfigException.class, () -> loader.loadFromPath(bad.toString()));

        GatewayConfig config = loader.getConfig();
        assertNotNull(config);
        assertTrue(config.routes() == null || config.routes().isEmpty());
    }

    @Test
    void validFile_loadsRoutesAndAuth() throws IOException {
        Path ok = Files.createTempFile("ok-routes", ".yaml");
        Files.writeString(
                ok,
                """
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
    void routeWithoutUpstream_failsFast() {
        RouteConfig invalid =
                new RouteConfig("no-upstream", "/broken/**", List.of("GET"), null, null);

        assertThrows(
                ConfigException.class,
                () -> loader.applyNewConfig(new GatewayConfig(List.of(invalid))));
    }

    @Test
    void zeroRateLimitWindow_failsFast() {
        // A non-positive window resets the bucket on every request, silently
        // disabling the limit; reject it instead of allowing everything.
        RouteConfig invalid =
                new RouteConfig(
                        "bad-window",
                        "/bad/**",
                        List.of("GET"),
                        "backend:8080",
                        FilterConfig.builder().rateLimit(new RateLimitConfig(10, 0)).build());

        ConfigException ex =
                assertThrows(
                        ConfigException.class,
                        () -> loader.applyNewConfig(new GatewayConfig(List.of(invalid))));
        assertTrue(ex.getMessage().contains("windowMs"));
    }

    @Test
    void duplicateRouteIdsOnInitialLoad_failFast() throws IOException {
        Path dup = Files.createTempFile("dup-routes", ".yaml");
        Files.writeString(
                dup,
                """
                routes:
                  - id: same-id
                    path: /first/**
                    upstream: backend-a:8080
                  - id: same-id
                    path: /second/**
                    upstream: backend-b:8080
                """);

        assertThrows(ConfigException.class, () -> loader.loadFromPath(dup.toString()));
    }

    @Test
    void invalidReload_keepsCurrentConfigAndDoesNotThrow() throws IOException {
        RouteConfig valid =
                new RouteConfig("keep", "/keep/**", List.of("GET"), "backend:8080", null);
        loader.applyNewConfig(new GatewayConfig(List.of(valid)));

        Path bad = Files.createTempFile("bad-auth", ".yaml");
        Files.writeString(
                bad,
                """
                routes:
                  - id: broken
                    path: /broken/**
                    upstream: backend:8080
                    filters:
                      auth:
                        type: jwt
                        required: true
                        secret: ""
                """);

        // A hot reload with an invalid auth config must not terminate the watcher
        // or replace the last known-good route table.
        assertDoesNotThrow(() -> loader.reloadConfig(bad));

        assertNotNull(loader.findMatchingRoute("/keep/x", "GET"));
        assertNull(loader.findMatchingRoute("/broken/x", "GET"));
    }
}
