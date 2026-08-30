package com.github.dropguard.loom.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.cache.LocalCacheManager;
import com.github.dropguard.loom.cache.LocalCacheManager.CacheEntry;
import com.github.dropguard.loom.metrics.GatewayMetrics;
import com.github.dropguard.loom.model.RouteConfig;
import com.github.dropguard.loom.model.RouteConfig.*;
import com.github.dropguard.loom.testutil.MockRequest;
import com.github.dropguard.loom.testutil.MockResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CacheFilterTest {

    private CacheFilter filter;
    private LocalCacheManager cache;
    private MockRequest request;
    private MockResponse response;
    private FilterContext context;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cache = new LocalCacheManager();
        GatewayMetrics metrics = new GatewayMetrics(new SimpleMeterRegistry());

        // Create a simple Instance wrapper for testing
        Instance<LocalCacheManager> instance =
                (Instance<LocalCacheManager>)
                        java.lang.reflect.Proxy.newProxyInstance(
                                Instance.class.getClassLoader(),
                                new Class<?>[] {Instance.class},
                                (proxy, method, args) -> {
                                    return switch (method.getName()) {
                                        case "get" -> cache;
                                        case "iterator" -> java.util.List.of(cache).iterator();
                                        case "stream" -> Stream.of(cache);
                                        case "isUnsatisfied" -> false;
                                        case "isAmbiguous" -> false;
                                        case "isResolvable" -> true;
                                        case "select" -> proxy;
                                        default -> null;
                                    };
                                });

        // Instance wrapper for CircuitBreakerRegistry (nullable in this test's
        // cache-hit path; the registry is only touched on cache hits with a
        // breaker-configured route).
        Instance<CircuitBreakerRegistry> breakerInstance =
                (Instance<CircuitBreakerRegistry>)
                        java.lang.reflect.Proxy.newProxyInstance(
                                Instance.class.getClassLoader(),
                                new Class<?>[] {Instance.class},
                                (proxy, method, args) -> {
                                    return switch (method.getName()) {
                                        case "isResolvable" -> false;
                                        case "isUnsatisfied" -> false;
                                        case "isAmbiguous" -> false;
                                        default -> null;
                                    };
                                });

        filter = new CacheFilter(instance, metrics, breakerInstance);
        request = new MockRequest();
        response = new MockResponse();
        context = new FilterContext(request, response);
    }

    private boolean applyAndCheckNext(FilterContext ctx) throws Exception {
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        filter.apply(ctx, () -> nextCalled.set(true));
        return nextCalled.get();
    }

    private RouteConfig routeWithCache(CacheConfig cacheConfig) {
        return new RouteConfig(
                "r1",
                "/**",
                null,
                "localhost:8080",
                FilterConfig.builder().cache(cacheConfig).build());
    }

    @Test
    void testCacheHitReturnsCachedResponse() throws Exception {
        // Pre-populate cache
        cache.put(
                "GET:/api/data|up=localhost:8080",
                200,
                "application/json",
                "{\"cached\":true}".getBytes(),
                60);

        context.route(routeWithCache(new CacheConfig(true, 60)));

        assertFalse(applyAndCheckNext(context)); // next.run() should NOT be called
        assertEquals(200, response.statusCode);
        assertEquals("{\"cached\":true}", response.body);
        assertTrue(response.ended);
    }

    @Test
    void testCacheMissCallsNext() throws Exception {
        context.route(routeWithCache(new CacheConfig(true, 60)));

        assertTrue(applyAndCheckNext(context)); // next.run() SHOULD be called
        assertFalse(response.ended); // response should not be ended yet
    }

    @Test
    void testNonGetSkipsCache() throws Exception {
        // Pre-populate cache
        cache.put("POST:/api/data", 200, "application/json", "{\"cached\":true}".getBytes(), 60);

        request.method = io.vertx.core.http.HttpMethod.POST;
        context.route(routeWithCache(new CacheConfig(true, 60)));

        assertTrue(applyAndCheckNext(context)); // next.run() SHOULD be called (cache skipped)
    }

    @Test
    void testCacheDisabledSkipsCache() throws Exception {
        // Pre-populate cache
        cache.put("GET:/api/data", 200, "application/json", "{\"cached\":true}".getBytes(), 60);

        context.route(routeWithCache(new CacheConfig(false, 60)));

        assertTrue(applyAndCheckNext(context)); // next.run() SHOULD be called (cache disabled)
    }

    @Test
    void testCacheKeyWithJwtIdentity() throws Exception {
        // Pre-populate cache with user-specific key
        cache.put(
                "GET:/api/data|up=localhost:8080:user-1",
                200,
                "application/json",
                "{\"user\":\"user-1\"}".getBytes(),
                60);

        context.route(routeWithCache(new CacheConfig(true, 60)));
        context.claims(Map.of("sub", "user-1"));

        assertFalse(applyAndCheckNext(context)); // cache hit
        assertEquals("{\"user\":\"user-1\"}", response.body);
    }

    @Test
    void testCacheKeyDifferentiatesUsers() throws Exception {
        // Pre-populate cache for user-1 only
        cache.put(
                "GET:/api/data:user-1",
                200,
                "application/json",
                "{\"user\":\"user-1\"}".getBytes(),
                60);

        context.route(routeWithCache(new CacheConfig(true, 60)));
        context.claims(Map.of("sub", "user-2")); // different user

        assertTrue(applyAndCheckNext(context)); // cache miss for user-2
    }

    // --- DEFECT #12: authenticated routes must not let an anonymous response
    // pollute an authenticated caller, and vice versa. ---

    @Test
    void anonResponseDoesNotPolluteAuthenticatedCaller_onAuthRoute() throws Exception {
        // A public ("please log in") body was cached under the :anon key.
        cache.put(
                "GET:/api/data:anon", 200, "application/json", "{\"public\":true}".getBytes(), 60);

        // An authenticated user arrives on the SAME uri.
        RouteConfig authRoute = routeWithAuthCache(new CacheConfig(true, 60), "jwt");
        context.route(authRoute);
        context.claims(Map.of("sub", "user-1"));

        // Must be a cache MISS — must NOT serve the anonymous body.
        assertTrue(applyAndCheckNext(context));
        assertNotEquals("{\"public\":true}", response.body);
    }

    @Test
    void anonymousCallerHitsAnonPartition_onAuthRoute() throws Exception {
        cache.put(
                "GET:/api/data|up=localhost:8080:anon",
                200,
                "application/json",
                "{\"public\":true}".getBytes(),
                60);

        // No claims, route supports auth -> key should be :anon -> cache HIT.
        context.route(routeWithAuthCache(new CacheConfig(true, 60), "jwt"));

        assertFalse(applyAndCheckNext(context));
        assertEquals("{\"public\":true}", response.body);
        // Vary header signals the representation varies by auth.
        assertEquals("Authorization", response.headers().get("Vary"));
    }

    @Test
    void noAuthRouteStillSharesPlainKey() throws Exception {
        // A route with NO auth config: anonymous responses are safe to share.
        cache.put(
                "GET:/api/data|up=localhost:8080",
                200,
                "application/json",
                "{\"shared\":true}".getBytes(),
                60);

        context.route(routeWithCache(new CacheConfig(true, 60))); // auth == null

        assertFalse(applyAndCheckNext(context));
        assertEquals("{\"shared\":true}", response.body);
    }

    private RouteConfig routeWithAuthCache(CacheConfig cacheConfig, String authType) {
        AuthConfig auth = new AuthConfig(authType, true, "secret", null);
        return new RouteConfig(
                "r-auth",
                "/**",
                null,
                "localhost:8080",
                FilterConfig.builder().auth(auth).cache(cacheConfig).build());
    }

    @Test
    void testVaryHeader_isAuthorization_forJwtRoute() throws Exception {
        RouteConfig route =
                new RouteConfig(
                        "r-jwt",
                        "/**",
                        null,
                        "localhost:8080",
                        FilterConfig.builder()
                                .auth(new RouteConfig.AuthConfig("jwt", true, "secret", null))
                                .cache(new RouteConfig.CacheConfig(true, 60))
                                .build());
        context.route(route);
        context.claims(Map.of("sub", "user123"));

        CacheEntry entry = new CacheEntry(200, "application/json", new byte[0], 0L);
        filter.writeCachedResponse(context, entry);

        assertEquals("Authorization", response.headers().get("Vary"));
    }

    @Test
    void testVaryHeader_isXApiKey_forApiKeyRoute() throws Exception {
        RouteConfig route =
                new RouteConfig(
                        "r-apikey",
                        "/**",
                        null,
                        "localhost:8080",
                        FilterConfig.builder()
                                .auth(
                                        new RouteConfig.AuthConfig(
                                                "api-key", true, null, List.of("key1")))
                                .cache(new RouteConfig.CacheConfig(true, 60))
                                .build());
        context.route(route);
        request.headers.put("X-API-Key", "key1");

        CacheEntry entry = new CacheEntry(200, "application/json", new byte[0], 0L);
        filter.writeCachedResponse(context, entry);

        assertEquals("X-API-Key", response.headers().get("Vary"));
    }

    @Test
    void testVaryHeader_notSet_forNoAuthRoute() throws Exception {
        RouteConfig route =
                new RouteConfig(
                        "r-noauth",
                        "/**",
                        null,
                        "localhost:8080",
                        FilterConfig.builder()
                                .cache(new RouteConfig.CacheConfig(true, 60))
                                .build());
        context.route(route);
        // No auth config, no identity needed

        CacheEntry entry = new CacheEntry(200, "application/json", new byte[0], 0L);
        filter.writeCachedResponse(context, entry);

        assertNull(response.headers().get("Vary"));
    }
}
