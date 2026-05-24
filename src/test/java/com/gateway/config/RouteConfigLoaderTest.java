package com.gateway.config;

import com.gateway.model.GatewayConfig;
import com.gateway.model.RouteConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteConfigLoaderTest {

    private RouteConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new RouteConfigLoader();
    }

    @Test
    void testPathPatternMatching_simplePath() {
        applyRoutes(List.of(
            new RouteConfig("health", "/health", List.of("GET"), "health-service:8080", null)
        ));

        assertNotNull(loader.findMatchingRoute("/health", "GET"));
        assertNull(loader.findMatchingRoute("/health", "POST"));
        assertNull(loader.findMatchingRoute("/healthz", "GET"));
    }

    @Test
    void testPathPatternMatching_doubleWildcard() {
        applyRoutes(List.of(
            new RouteConfig("api-users", "/api/users/**", List.of("GET", "POST"), "user-service:8080", null)
        ));

        assertNotNull(loader.findMatchingRoute("/api/users", "GET"));
        assertNotNull(loader.findMatchingRoute("/api/users/123", "GET"));
        assertNotNull(loader.findMatchingRoute("/api/users/123/profile", "POST"));
        assertNull(loader.findMatchingRoute("/api/users/123", "DELETE"));
        assertNull(loader.findMatchingRoute("/api/orders", "GET"));
    }

    @Test
    void testPathPatternMatching_singleWildcard() {
        applyRoutes(List.of(
            new RouteConfig("api-item", "/api/items/*", List.of("GET"), "item-service:8080", null)
        ));

        assertNotNull(loader.findMatchingRoute("/api/items/123", "GET"));
        assertNull(loader.findMatchingRoute("/api/items/123/sub", "GET"));
    }

    @Test
    void testPathPatternMatching_nullMethods() {
        applyRoutes(List.of(
            new RouteConfig("catch-all", "/**", null, "backend:8080", null)
        ));

        assertNotNull(loader.findMatchingRoute("/anything/goes", "GET"));
        assertNotNull(loader.findMatchingRoute("/anything/goes", "POST"));
    }

    @Test
    void testPathPatternMatching_emptyMethods() {
        applyRoutes(List.of(
            new RouteConfig("catch-all", "/**", List.of(), "backend:8080", null)
        ));

        assertNotNull(loader.findMatchingRoute("/anything/goes", "GET"));
    }

    private void applyRoutes(List<RouteConfig> routes) {
        loader.applyNewConfig(new GatewayConfig(routes));
    }
}
