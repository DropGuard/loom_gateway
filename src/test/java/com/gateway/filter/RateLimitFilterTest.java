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
}
