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

    private RouteConfig routeWithAuthCache(CacheConfig cacheConfig, String authType) {
        AuthConfig auth = new AuthConfig(authType, true, "secret", null);
        return new RouteConfig(
                "r-auth",
                "/**",
                null,
                "localhost:8080",
                FilterConfig.builder().auth(auth).cache(cacheConfig).build());
    }

    private FilterContext contextWithSubject(String sub) {
        FilterContext ctx = new FilterContext(new MockRequest(), new MockResponse());
        ctx.route(routeWithAuthCache(new CacheConfig(true, 60), "jwt"));
        ctx.claims(Map.of("sub", sub));
        return ctx;
    }

    private static String authenticatedKey(String sub) {
        return "GET:/api/data|up=localhost:8080:" + sub;
    }

    @Test
    void cacheHit_servesCachedBodyWithoutCallingNext() throws Exception {
        cache.put(
                "GET:/api/data|up=localhost:8080",
                200,
                "application/json",
                "{\"cached\":true}".getBytes(),
                60);

        context.route(routeWithCache(new CacheConfig(true, 60)));

        assertFalse(applyAndCheckNext(context));
        assertEquals(200, response.statusCode);
        assertEquals("{\"cached\":true}", response.body);
        assertTrue(response.ended);
        assertNull(response.headers().get("Vary"), "unauthenticated routes never vary by auth");
    }

    @Test
    void cacheMiss_callsNextAndSetsVary() throws Exception {
        context.route(routeWithAuthCache(new CacheConfig(true, 60), "jwt"));
        context.claims(Map.of("sub", "user1"));

        assertTrue(applyAndCheckNext(context), "expected a cache miss");
        assertFalse(response.ended);
        assertEquals("Authorization", response.headers().get("Vary"));
    }

    @Test
    void nonGetRequest_skipsCache() throws Exception {
        request.method = io.vertx.core.http.HttpMethod.POST;
        context.route(routeWithCache(new CacheConfig(true, 60)));

        assertTrue(applyAndCheckNext(context));
    }

    @Test
    void disabledCache_skipsCache() throws Exception {
        context.route(routeWithCache(new CacheConfig(false, 60)));

        assertTrue(applyAndCheckNext(context));
    }

    @Test
    void jwtSubject_partitionsCache() throws Exception {
        cache.put(
                authenticatedKey("user-1"),
                200,
                "application/json",
                "{\"user\":\"user-1\"}".getBytes(),
                60);
        cache.put(
                authenticatedKey("user-2"),
                200,
                "application/json",
                "{\"user\":\"user-2\"}".getBytes(),
                60);

        FilterContext user1 = contextWithSubject("user-1");
        assertFalse(applyAndCheckNext(user1));
        assertEquals("{\"user\":\"user-1\"}", ((MockResponse) user1.response()).body);

        FilterContext user2 = contextWithSubject("user-2");
        assertFalse(applyAndCheckNext(user2));
        assertEquals("{\"user\":\"user-2\"}", ((MockResponse) user2.response()).body);
    }

    @Test
    void anonymousAndAuthenticated_useSeparatePartitions() throws Exception {
        cache.put(
                "GET:/api/data|up=localhost:8080:anon",
                200,
                "application/json",
                "{\"public\":true}".getBytes(),
                60);
        cache.put(
                authenticatedKey("user-1"),
                200,
                "application/json",
                "{\"user\":\"user-1\"}".getBytes(),
                60);

        FilterContext authenticated = contextWithSubject("user-1");
        assertFalse(applyAndCheckNext(authenticated));
        assertEquals("{\"user\":\"user-1\"}", ((MockResponse) authenticated.response()).body);

        FilterContext anonymous = new FilterContext(new MockRequest(), new MockResponse());
        anonymous.route(routeWithAuthCache(new CacheConfig(true, 60), "jwt"));
        assertFalse(applyAndCheckNext(anonymous));
        MockResponse anonymousResponse = (MockResponse) anonymous.response();
        assertEquals("{\"public\":true}", anonymousResponse.body);
        assertEquals("Authorization", anonymousResponse.headers().get("Vary"));
    }

    @Test
    void varyHeader_namesAuthHeader_forAuthenticatedRoutes() {
        CacheEntry entry = new CacheEntry(200, "application/json", new byte[0], 0L);

        for (String authType : List.of("jwt", "api-key")) {
            MockResponse resp = new MockResponse();
            FilterContext ctx = new FilterContext(new MockRequest(), resp);
            ctx.route(routeWithAuthCache(new CacheConfig(true, 60), authType));

            filter.writeCachedResponse(ctx, entry);

            String expected = "api-key".equals(authType) ? "X-API-Key" : "Authorization";
            assertEquals(expected, resp.headers().get("Vary"), authType);
        }
    }

    @Test
    void authTypeNone_isTreatedAsAnonymous() throws Exception {
        // `type: none` declares the route as public; it must not produce a Vary
        // header nor an :anon cache partition.
        cache.put(
                "GET:/api/data|up=localhost:8080",
                200,
                "application/json",
                "{\"public\":true}".getBytes(),
                60);

        context.route(
                new RouteConfig(
                        "r-none",
                        "/**",
                        null,
                        "localhost:8080",
                        FilterConfig.builder()
                                .auth(new AuthConfig("none", false, null, null))
                                .cache(new CacheConfig(true, 60))
                                .build()));

        assertFalse(applyAndCheckNext(context), "public route must share the bare cache key");
        assertNull(response.headers().get("Vary"));
    }
}
