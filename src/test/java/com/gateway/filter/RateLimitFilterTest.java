package com.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.FilterConfig;
import com.gateway.model.RouteConfig.RateLimitConfig;
import com.gateway.testutil.MockRequest;
import com.gateway.testutil.MockResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private MockRequest request;
    private MockResponse response;
    private FilterContext context;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        request = new MockRequest();
        response = new MockResponse();
        context = new FilterContext(request, response);
    }

    private boolean applyAndCheckNext(FilterContext ctx) throws Exception {
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        filter.apply(ctx, () -> nextCalled.set(true));
        return nextCalled.get();
    }

    private RouteConfig routeWithRateLimit(String id, RateLimitConfig rl) {
        return new RouteConfig(id, "/**", null, "", FilterConfig.builder().rateLimit(rl).build());
    }

    @Test
    void testNoRateLimitConfigPasses() throws Exception {
        context.route(new RouteConfig("r1", "/**", null, "", null));
        assertTrue(applyAndCheckNext(context));
    }

    @Test
    void testRateLimitAllowsWithinLimit() throws Exception {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(5, 1000)));

        for (int i = 0; i < 5; i++) {
            response = new MockResponse();
            context = new FilterContext(request, response);
            context.route(routeWithRateLimit("r1", new RateLimitConfig(5, 1000)));
            assertTrue(applyAndCheckNext(context));
        }
    }

    @Test
    void testRateLimitBlocksAfterExceeding() throws Exception {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(3, 1000)));

        assertTrue(applyAndCheckNext(context));
        assertTrue(applyAndCheckNext(context));
        assertTrue(applyAndCheckNext(context));

        assertFalse(applyAndCheckNext(context));
        assertEquals(429, response.statusCode);
    }

    @Test
    void testDifferentRoutesHaveSeparateLimits() throws Exception {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(1, 1000)));
        assertTrue(applyAndCheckNext(context));
        assertFalse(applyAndCheckNext(context));

        context.route(routeWithRateLimit("r2", new RateLimitConfig(1, 1000)));
        assertTrue(applyAndCheckNext(context));
    }

    @Test
    void testWindowResets() throws Exception {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(2, 100)));

        assertTrue(applyAndCheckNext(context));
        assertTrue(applyAndCheckNext(context));
        assertFalse(applyAndCheckNext(context));

        Thread.sleep(150);

        assertTrue(applyAndCheckNext(context));
    }

    // --- DEFECT #8: per-client buckets so one client cannot starve others ---

    @Test
    void differentClientsAreRateLimitedIndependently() throws Exception {
        // route limit 2; client A uses 2, client B should still get its own 2.
        RateLimitConfig cfg = new RateLimitConfig(2, 1000);

        // Client A: 2 allowed, 3rd blocked.
        for (int i = 0; i < 2; i++) {
            request = new MockRequest();
            request.headers.put("X-API-Key", "client-A");
            response = new MockResponse();
            context = new FilterContext(request, response);
            context.route(routeWithRateLimit("r1", cfg));
            assertTrue(applyAndCheckNext(context), "client A req " + (i + 1));
        }
        request = new MockRequest();
        request.headers.put("X-API-Key", "client-A");
        response = new MockResponse();
        context = new FilterContext(request, response);
        context.route(routeWithRateLimit("r1", cfg));
        assertFalse(applyAndCheckNext(context), "client A should be blocked on 3rd");

        // Client B: independent bucket, gets its full 2 even though A is blocked.
        for (int i = 0; i < 2; i++) {
            request = new MockRequest();
            request.headers.put("X-API-Key", "client-B");
            response = new MockResponse();
            context = new FilterContext(request, response);
            context.route(routeWithRateLimit("r1", cfg));
            assertTrue(applyAndCheckNext(context), "client B req " + (i + 1) + " (independent)");
        }
    }

    @Test
    void jwtSubIdentifiesClient() throws Exception {
        RateLimitConfig cfg = new RateLimitConfig(1, 1000);

        // user-1 consumes the single slot.
        context.route(routeWithRateLimit("r1", cfg));
        context.claims(java.util.Map.of("sub", "user-1"));
        assertTrue(applyAndCheckNext(context));

        // user-2 (different sub) gets an independent bucket -> allowed.
        request = new MockRequest();
        response = new MockResponse();
        context = new FilterContext(request, response);
        context.route(routeWithRateLimit("r1", cfg));
        context.claims(java.util.Map.of("sub", "user-2"));
        assertTrue(applyAndCheckNext(context), "user-2 independent of user-1");

        // user-1 again -> blocked.
        request = new MockRequest();
        response = new MockResponse();
        context = new FilterContext(request, response);
        context.route(routeWithRateLimit("r1", cfg));
        context.claims(java.util.Map.of("sub", "user-1"));
        assertFalse(applyAndCheckNext(context), "user-1 blocked after using quota");
    }

    @Test
    void noIdentityFallsBackToRouteWideBucket() throws Exception {
        // No claims, no API key -> clientId null -> route-wide shared bucket.
        RateLimitConfig cfg = new RateLimitConfig(1, 1000);

        context.route(routeWithRateLimit("r1", cfg));
        assertTrue(applyAndCheckNext(context)); // first anonymous allowed

        request = new MockRequest();
        response = new MockResponse();
        context = new FilterContext(request, response);
        context.route(routeWithRateLimit("r1", cfg));
        assertFalse(applyAndCheckNext(context), "second anonymous shares route bucket -> blocked");
    }
}
