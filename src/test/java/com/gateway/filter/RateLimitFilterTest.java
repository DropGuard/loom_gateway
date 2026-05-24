package com.gateway.filter;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.RateLimitConfig;
import com.gateway.testutil.MockRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private MockRequest request;
    private FilterContext context;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        request = new MockRequest();
        context = new FilterContext(request, null);
    }

    @Test
    void testNoRateLimitConfigPasses() {
        context.route(new RouteConfig("r1", "/**", null, "", null, null, null, null, null));
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testRateLimitAllowsWithinLimit() {
        RouteConfig route = new RouteConfig("r1", "/**", null, "",
                null, new RateLimitConfig(5, 1000), null, null, null);
        context.route(route);

        for (int i = 0; i < 5; i++) {
            assertTrue(filter.filter(context).continueChain());
        }
    }

    @Test
    void testRateLimitBlocksAfterExceeding() {
        RouteConfig route = new RouteConfig("r1", "/**", null, "",
                null, new RateLimitConfig(3, 1000), null, null, null);
        context.route(route);

        assertTrue(filter.filter(context).continueChain()); // 1
        assertTrue(filter.filter(context).continueChain()); // 2
        assertTrue(filter.filter(context).continueChain()); // 3

        FilterResult result = filter.filter(context);
        assertFalse(result.continueChain());
        assertEquals(429, result.statusCode());
    }

    @Test
    void testDifferentRoutesHaveSeparateLimits() {
        RouteConfig route1 = new RouteConfig("r1", "/api/a", null, "",
                null, new RateLimitConfig(1, 1000), null, null, null);
        RouteConfig route2 = new RouteConfig("r2", "/api/b", null, "",
                null, new RateLimitConfig(1, 1000), null, null, null);

        context.route(route1);
        assertTrue(filter.filter(context).continueChain());
        assertFalse(filter.filter(context).continueChain());

        context.route(route2);
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testWindowResets() throws InterruptedException {
        RouteConfig route = new RouteConfig("r1", "/**", null, "",
                null, new RateLimitConfig(2, 100), null, null, null);
        context.route(route);

        assertTrue(filter.filter(context).continueChain());
        assertTrue(filter.filter(context).continueChain());
        assertFalse(filter.filter(context).continueChain());

        Thread.sleep(150);

        assertTrue(filter.filter(context).continueChain());
    }
}
