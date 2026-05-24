package com.gateway.config;

import com.gateway.model.GatewayConfig;
import com.gateway.model.RouteConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RouteConfigLoaderTest {

    private RouteConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new RouteConfigLoader();
    }

    @Test
    void testPathPatternMatching_simplePath() throws Exception {
        var config = createConfig(List.of(
            new RouteConfig("health", "/health", List.of("GET"), "health-service:8080", null, null, null, null, null)
        ));
        applyConfig(loader, config);

        assertNotNull(loader.findMatchingRoute("/health", "GET"));
        assertNull(loader.findMatchingRoute("/health", "POST"));
        assertNull(loader.findMatchingRoute("/healthz", "GET"));
    }

    @Test
    void testPathPatternMatching_doubleWildcard() throws Exception {
        var config = createConfig(List.of(
            new RouteConfig("api-users", "/api/users/**", List.of("GET", "POST"), "user-service:8080", null, null, null, null, null)
        ));
        applyConfig(loader, config);

        assertNotNull(loader.findMatchingRoute("/api/users", "GET"));
        assertNotNull(loader.findMatchingRoute("/api/users/123", "GET"));
        assertNotNull(loader.findMatchingRoute("/api/users/123/profile", "POST"));
        assertNull(loader.findMatchingRoute("/api/users/123", "DELETE"));
        assertNull(loader.findMatchingRoute("/api/orders", "GET"));
    }

    @Test
    void testPathPatternMatching_singleWildcard() throws Exception {
        var config = createConfig(List.of(
            new RouteConfig("api-item", "/api/items/*", List.of("GET"), "item-service:8080", null, null, null, null, null)
        ));
        applyConfig(loader, config);

        assertNotNull(loader.findMatchingRoute("/api/items/123", "GET"));
        assertNull(loader.findMatchingRoute("/api/items/123/sub", "GET")); // single * doesn't match /
    }

    @Test
    void testPathPatternMatching_nullMethods() throws Exception {
        var config = createConfig(List.of(
            new RouteConfig("catch-all", "/**", null, "backend:8080", null, null, null, null, null)
        ));
        applyConfig(loader, config);

        assertNotNull(loader.findMatchingRoute("/anything/goes", "GET"));
        assertNotNull(loader.findMatchingRoute("/anything/goes", "POST"));
    }

    @Test
    void testPathPatternMatching_emptyMethods() throws Exception {
        var config = createConfig(List.of(
            new RouteConfig("catch-all", "/**", List.of(), "backend:8080", null, null, null, null, null)
        ));
        applyConfig(loader, config);

        assertNotNull(loader.findMatchingRoute("/anything/goes", "GET"));
    }

    private GatewayConfig createConfig(List<RouteConfig> routes) {
        return new GatewayConfig(routes);
    }

    private void applyConfig(RouteConfigLoader loader, GatewayConfig config) throws Exception {
        var configField = RouteConfigLoader.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(loader, config);
        
        var patternsField = RouteConfigLoader.class.getDeclaredField("compiledPatterns");
        patternsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var patterns = (Map<String, Pattern>) patternsField.get(loader);
        patterns.clear();
        
        Method compileMethod = RouteConfigLoader.class.getDeclaredMethod("compilePathPattern", String.class);
        compileMethod.setAccessible(true);
        
        for (RouteConfig route : config.routes()) {
            patterns.put(route.id(), (Pattern) compileMethod.invoke(loader, route.path()));
        }
    }
}
