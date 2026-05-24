package com.gateway.filter;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.FilterConfig;
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

    private RouteConfig routeWithRateLimit(String id, RateLimitConfig rl) {
        return new RouteConfig(id, "/**", null, "", FilterConfig.builder().rateLimit(rl).build());
    }

    @Test
    void testNoRateLimitConfigPasses() {
        context.route(new RouteConfig("r1", "/**", null, "", null));
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testRateLimitAllowsWithinLimit() {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(5, 1000)));

        for (int i = 0; i < 5; i++) {
            assertTrue(filter.filter(context).continueChain());
        }
    }

    @Test
    void testRateLimitBlocksAfterExceeding() {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(3, 1000)));

        assertTrue(filter.filter(context).continueChain());
        assertTrue(filter.filter(context).continueChain());
        assertTrue(filter.filter(context).continueChain());

        FilterResult result = filter.filter(context);
        assertFalse(result.continueChain());
        assertEquals(429, result.statusCode());
    }

    @Test
    void testDifferentRoutesHaveSeparateLimits() {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(1, 1000)));
        assertTrue(filter.filter(context).continueChain());
        assertFalse(filter.filter(context).continueChain());

        context.route(routeWithRateLimit("r2", new RateLimitConfig(1, 1000)));
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testWindowResets() throws InterruptedException {
        context.route(routeWithRateLimit("r1", new RateLimitConfig(2, 100)));

        assertTrue(filter.filter(context).continueChain());
        assertTrue(filter.filter(context).continueChain());
        assertFalse(filter.filter(context).continueChain());

        Thread.sleep(150);

        assertTrue(filter.filter(context).continueChain());
    }
}
