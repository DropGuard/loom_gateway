package com.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.gateway.cache.LocalCacheManager;
import com.gateway.metrics.GatewayMetrics;
import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.*;
import com.gateway.testutil.MockRequest;
import com.gateway.testutil.MockResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.enterprise.inject.Instance;

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
        Instance<LocalCacheManager> instance = (Instance<LocalCacheManager>) java.lang.reflect.Proxy.newProxyInstance(
                Instance.class.getClassLoader(),
                new Class<?>[] { Instance.class },
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

        filter = new CacheFilter(instance, metrics);
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
        return new RouteConfig("r1", "/**", null, "localhost:8080",
                FilterConfig.builder().cache(cacheConfig).build());
    }

    @Test
    void testCacheHitReturnsCachedResponse() throws Exception {
        // Pre-populate cache
        cache.put("GET:/api/data", 200, "application/json", "{\"cached\":true}".getBytes(), 60);

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
        cache.put("GET:/api/data:user-1", 200, "application/json", "{\"user\":\"user-1\"}".getBytes(), 60);

        context.route(routeWithCache(new CacheConfig(true, 60)));
        context.claims(Map.of("sub", "user-1"));

        assertFalse(applyAndCheckNext(context)); // cache hit
        assertEquals("{\"user\":\"user-1\"}", response.body);
    }

    @Test
    void testCacheKeyDifferentiatesUsers() throws Exception {
        // Pre-populate cache for user-1 only
        cache.put("GET:/api/data:user-1", 200, "application/json", "{\"user\":\"user-1\"}".getBytes(), 60);

        context.route(routeWithCache(new CacheConfig(true, 60)));
        context.claims(Map.of("sub", "user-2")); // different user

        assertTrue(applyAndCheckNext(context)); // cache miss for user-2
    }
}
