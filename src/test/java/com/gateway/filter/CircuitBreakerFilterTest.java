package com.gateway.filter;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.CircuitBreakerConfig;
import com.gateway.model.RouteConfig.FilterConfig;
import com.gateway.testutil.MockRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerFilterTest {

    private CircuitBreakerFilter filter;
    private MockRequest request;
    private FilterContext context;

    @BeforeEach
    void setUp() {
        filter = new CircuitBreakerFilter();
        request = new MockRequest();
        context = new FilterContext(request, null);
    }

    private RouteConfig routeWithCB(CircuitBreakerConfig cb) {
        return new RouteConfig("r1", "/**", null, "", FilterConfig.builder().circuitBreaker(cb).build());
    }

    @Test
    void testNoCircuitBreakerConfigPasses() {
        context.route(new RouteConfig("r1", "/**", null, "", null));
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testAllowsRequestsInitially() {
        context.route(routeWithCB(new CircuitBreakerConfig(3, 1000)));
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testOpensAfterThreshold() {
        context.route(routeWithCB(new CircuitBreakerConfig(3, 5000)));

        assertTrue(filter.filter(context).continueChain());

        filter.recordOutcome("r1", false);
        filter.recordOutcome("r1", false);
        filter.recordOutcome("r1", false);

        FilterResult result = filter.filter(context);
        assertFalse(result.continueChain());
        assertEquals(503, result.statusCode());
    }

    @Test
    void testHalfOpenAfterTimeout() throws InterruptedException {
        context.route(routeWithCB(new CircuitBreakerConfig(2, 200)));

        assertTrue(filter.filter(context).continueChain());

        filter.recordOutcome("r1", false);
        filter.recordOutcome("r1", false);
        assertFalse(filter.filter(context).continueChain());

        Thread.sleep(250);

        assertTrue(filter.filter(context).continueChain());

        filter.recordOutcome("r1", true);
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testHalfOpenBackToOpenOnFailure() throws InterruptedException {
        context.route(routeWithCB(new CircuitBreakerConfig(2, 100)));

        assertTrue(filter.filter(context).continueChain());

        filter.recordOutcome("r1", false);
        filter.recordOutcome("r1", false);
        assertFalse(filter.filter(context).continueChain());

        Thread.sleep(150);

        assertTrue(filter.filter(context).continueChain());

        filter.recordOutcome("r1", false);
        assertFalse(filter.filter(context).continueChain());
    }
}
