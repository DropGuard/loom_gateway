package com.gateway.integration;

import com.gateway.config.RouteConfigProvider;
import com.gateway.model.GatewayConfig;
import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.*;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Mock
@ApplicationScoped
public class TestRouteConfigProvider implements RouteConfigProvider {

    private static final String JWT_SECRET = "this-is-a-test-secret-key-that-is-at-least-32-chars!";

    private final GatewayConfig config;
    private final Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    public TestRouteConfigProvider() {
        List<RouteConfig> routes = List.of(
            new RouteConfig("api-users", "/api/users/**", List.of("GET", "POST"), "localhost:19999",
                FilterConfig.builder()
                    .auth(new AuthConfig("jwt", true, JWT_SECRET, null))
                    .rateLimit(new RateLimitConfig(100, 1000))
                    .circuitBreaker(new CircuitBreakerConfig(3, 5000))
                    .cache(new CacheConfig(true, 60))
                    .build()),

            new RouteConfig("api-public", "/api/public/**", List.of("GET"), "localhost:19999",
                FilterConfig.builder()
                    .auth(new AuthConfig("none", false, null, null))
                    .rateLimit(new RateLimitConfig(100, 1000))
                    .build()),

            new RouteConfig("api-key-service", "/api/external/**", List.of("GET"), "localhost:19999",
                FilterConfig.builder()
                    .auth(new AuthConfig("api-key", true, null, List.of("pk-test-key-123")))
                    .build()),

            new RouteConfig("api-echo", "/api/echo/**", List.of("POST"), "localhost:19999",
                FilterConfig.builder()
                    .auth(new AuthConfig("none", false, null, null))
                    .build()),

            new RouteConfig("api-fail", "/api/fail/**", List.of("GET"), "localhost:19998",
                FilterConfig.builder()
                    .auth(new AuthConfig("none", false, null, null))
                    .circuitBreaker(new CircuitBreakerConfig(3, 1000))
                    .build()),

            new RouteConfig("api-limited", "/api/limited/**", List.of("GET"), "localhost:19999",
                FilterConfig.builder()
                    .auth(new AuthConfig("none", false, null, null))
                    .rateLimit(new RateLimitConfig(3, 10000))
                    .build()),

            new RouteConfig("ws-chat", "/ws/chat/**", List.of("GET"), "localhost:19999",
                FilterConfig.builder()
                    .auth(new AuthConfig("jwt", true, JWT_SECRET, null))
                    .build()),

            new RouteConfig("ws-public", "/ws/public/**", List.of("GET"), "localhost:19999",
                FilterConfig.builder()
                    .auth(new AuthConfig("none", false, null, null))
                    .build())
        );

        this.config = new GatewayConfig(routes);
        for (RouteConfig route : routes) {
            compiledPatterns.put(route.id(), compilePathPattern(route.path()));
        }
    }

    @Override
    public RouteConfig findMatchingRoute(String path, String method) {
        for (RouteConfig route : config.routes()) {
            Pattern pattern = compiledPatterns.get(route.id());
            if (pattern != null && pattern.matcher(path).matches()) {
                if (route.methods() == null || route.methods().isEmpty()
                        || route.methods().contains(method.toUpperCase())) {
                    return route;
                }
            }
        }
        return null;
    }

    @Override
    public GatewayConfig getConfig() {
        return config;
    }

    private static Pattern compilePathPattern(String pathPattern) {
        String regex;
        if (pathPattern.endsWith("/**")) {
            String basePath = pathPattern.substring(0, pathPattern.length() - 3);
            regex = basePath + "(?:/.*)?";
        } else {
            regex = pathPattern
                .replace("**", "__DOUBLESTAR__")
                .replace("*", "[^/]+")
                .replace("__DOUBLESTAR__", ".*");
        }
        return Pattern.compile("^" + regex + "$");
    }
}
