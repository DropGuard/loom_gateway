package com.github.dropguard.loom.config;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.model.GatewayConfig;
import com.github.dropguard.loom.model.RouteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * T1: the PRODUCTION routes.yaml (config/routes.yaml) is loaded and validated, not just the @Mock
 * fake config used by integration tests. This catches malformed YAML / wrong field names / missing
 * upstreams that would otherwise only surface at deploy time (DEFECT #11 class of failure).
 */
class RouteConfigLoaderProductionTest {

    private static final String PROD_CONFIG = "config/routes.yaml";

    private GatewayConfig loadProdConfig() throws IOException {
        // Create a temporary file with actual values instead of placeholders
        Path tempFile = Files.createTempFile("routes-", ".yaml");
        try {
            String content = Files.readString(Path.of(PROD_CONFIG));
            content = content.replace("${JWT_SECRET:}", "test-jwt-secret");
            content = content.replace("${API_KEY_1:}", "test-api-key-1");
            content = content.replace("${API_KEY_2:}", "test-api-key-2");
            Files.writeString(tempFile, content);

            Path path = tempFile;
            assertTrue(Files.exists(path), "temp config must exist at " + path);
            RouteConfigLoader loader = new RouteConfigLoader();
            loader.loadFromPath(path.toString());
            GatewayConfig config = loader.getConfig();
            assertNotNull(config, "config must parse without throwing");
            assertNotNull(config.routes(), "routes must not be null");
            assertFalse(config.routes().isEmpty(), "config must contain routes");
            return config;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void productionConfigLoads() throws Exception {
        GatewayConfig config = loadProdConfig();
        // 3 original HTTP routes + 2 WebSocket routes added for DEFECT A1.
        assertEquals(5, config.routes().size(), "expected 5 routes (3 HTTP + 2 WS)");
    }

    @Test
    void productionConfigHasWebSocketRoutes() throws Exception {
        GatewayConfig config = loadProdConfig();
        assertTrue(
                config.routes().stream().anyMatch(r -> "ws-chat".equals(r.id())),
                "ws-chat route must exist");
        assertTrue(
                config.routes().stream().anyMatch(r -> "ws-public".equals(r.id())),
                "ws-public route must exist");
    }

    @Test
    void everyRouteHasUpstreamAndValidPath() throws Exception {
        GatewayConfig config = loadProdConfig();
        for (RouteConfig route : config.routes()) {
            assertNotNull(route.upstream(), "route '" + route.id() + "' must declare an upstream");
            assertFalse(
                    route.upstream().isBlank(),
                    "route '" + route.id() + "' upstream must not be blank");
            assertNotNull(route.path(), "route '" + route.id() + "' must declare a path");
            assertFalse(
                    route.path().isBlank(), "route '" + route.id() + "' path must not be blank");
        }
    }

    @Test
    void everyRouteCompilesItsPathPattern() throws Exception {
        // Create a temporary file with actual values instead of placeholders
        Path tempFile = Files.createTempFile("routes-", ".yaml");
        try {
            String content = Files.readString(Path.of(PROD_CONFIG));
            content = content.replace("${JWT_SECRET:}", "test-jwt-secret");
            content = content.replace("${API_KEY_1:}", "test-api-key-1");
            content = content.replace("${API_KEY_2:}", "test-api-key-2");
            Files.writeString(tempFile, content);

            GatewayConfig config = loadProdConfig();
            RouteConfigLoader loader = new RouteConfigLoader();
            loader.loadFromPath(tempFile.toString());
            for (RouteConfig route : config.routes()) {
                assertNotNull(
                        loader.findMatchingRoute(route.path().replace("**", "x"), "GET"),
                        "path pattern for '" + route.id() + "' must compile and match");
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
