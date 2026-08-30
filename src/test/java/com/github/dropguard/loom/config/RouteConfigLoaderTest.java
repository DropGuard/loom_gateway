package com.github.dropguard.loom.config;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.model.GatewayConfig;
import com.github.dropguard.loom.model.RouteConfig;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteConfigLoaderTest {

    private RouteConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new RouteConfigLoader();
    }

    @Test
    void testPathPatternMatching_simplePath() {
        applyRoutes(
                List.of(
                        new RouteConfig(
                                "health", "/health", List.of("GET"), "health-service:8080", null)));

        assertNotNull(loader.findMatchingRoute("/health", "GET"));
        assertNull(loader.findMatchingRoute("/health", "POST"));
        assertNull(loader.findMatchingRoute("/healthz", "GET"));
    }

    @Test
    void testPathPatternMatching_doubleWildcard() {
        applyRoutes(
                List.of(
                        new RouteConfig(
                                "api-users",
                                "/api/users/**",
                                List.of("GET", "POST"),
                                "user-service:8080",
                                null)));

        assertNotNull(loader.findMatchingRoute("/api/users", "GET"));
        assertNotNull(loader.findMatchingRoute("/api/users/123", "GET"));
        assertNotNull(loader.findMatchingRoute("/api/users/123/profile", "POST"));
        assertNull(loader.findMatchingRoute("/api/users/123", "DELETE"));
        assertNull(loader.findMatchingRoute("/api/orders", "GET"));
    }

    @Test
    void testPathPatternMatching_singleWildcard() {
        applyRoutes(
                List.of(
                        new RouteConfig(
                                "api-item",
                                "/api/items/*",
                                List.of("GET"),
                                "item-service:8080",
                                null)));

        assertNotNull(loader.findMatchingRoute("/api/items/123", "GET"));
        assertNull(loader.findMatchingRoute("/api/items/123/sub", "GET"));
    }

    @Test
    void testPathPatternMatching_nullMethods() {
        applyRoutes(List.of(new RouteConfig("catch-all", "/**", null, "backend:8080", null)));

        assertNotNull(loader.findMatchingRoute("/anything/goes", "GET"));
        assertNotNull(loader.findMatchingRoute("/anything/goes", "POST"));
    }

    @Test
    void testPathPatternMatching_emptyMethods() {
        applyRoutes(List.of(new RouteConfig("catch-all", "/**", List.of(), "backend:8080", null)));

        assertNotNull(loader.findMatchingRoute("/anything/goes", "GET"));
    }

    @Test
    void testCompilePathPattern_escapesMetacharacters() {
        // test various patterns
        assertPatternMatches("/api/v1.0", "/api/v1.0/**");
        assertPatternMatches("/api/v1.0/", "/api/v1.0/**");
        assertPatternMatches("/api/v1.0/users", "/api/v1.0/**");
        assertPatternMatches("/api/v1.0/users/123", "/api/v1.0/**");
        assertPatternNotMatch("/api/v1", "/api/v1.0/**");

        assertPatternMatches("/api/(v2)", "/api/(v2)/**");
        assertPatternMatches("/api/(v2)/", "/api/(v2)/**");
        assertPatternMatches("/api/(v2)/resource", "/api/(v2)/**");
        assertPatternNotMatch("/api/v2/", "/api/(v2)/**");
        assertPatternNotMatch("/api/(v3)/", "/api/(v2)/**");

        assertPatternMatches("/users/123/posts", "/users/*/posts");
        assertPatternMatches("/users/abc/posts", "/users/*/posts");
        assertPatternNotMatch("/users/123/posts/", "/users/*/posts");
        assertPatternNotMatch("/users/123/posts/extra", "/users/*/posts");

        assertPatternMatches("/static", "/static/**");
        assertPatternMatches("/static/", "/static/**");
        assertPatternMatches("/static/a/b/c", "/static/**");
    }

    private void assertPatternMatches(String input, String pattern) {
        Pattern p = loader.compilePathPattern(pattern);
        assertTrue(
                p.matcher(input).matches(),
                "Expected '" + input + "' to match pattern '" + pattern + "'");
    }

    private void assertPatternNotMatch(String input, String pattern) {
        Pattern p = loader.compilePathPattern(pattern);
        assertFalse(
                p.matcher(input).matches(),
                "Expected '" + input + "' NOT to match pattern '" + pattern + "'");
    }

    @Test
    void testDuplicateRouteId_keepsCurrentConfig() {
        // First, apply a valid configuration
        RouteConfig routeA =
                new RouteConfig("dup-id", "/first/**", List.of("GET"), "upstream-a:8080", null);
        loader.applyNewConfig(new GatewayConfig(List.of(routeA)));

        // Verify the first config is loaded
        assertNotNull(loader.findMatchingRoute("/first/path", "GET"));
        assertEquals("upstream-a:8080", loader.getConfig().routes().get(0).upstream());

        // Now apply a config with a duplicate ID (same ID, different upstream)
        RouteConfig routeB =
                new RouteConfig("dup-id", "/second/**", List.of("GET"), "upstream-b:8080", null);
        loader.applyNewConfig(new GatewayConfig(List.of(routeA, routeB)));

        // The duplicate config should be rejected; current config remains
        // (i.e. routeA still resolves, routeB is NOT loaded)
        assertNotNull(loader.findMatchingRoute("/first/path", "GET"));
        assertNull(loader.findMatchingRoute("/second/path", "GET"));
        assertEquals("upstream-a:8080", loader.getConfig().routes().get(0).upstream());
    }

    private void applyRoutes(List<RouteConfig> routes) {
        loader.applyNewConfig(new GatewayConfig(routes));
    }
}
