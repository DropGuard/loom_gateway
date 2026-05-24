package com.gateway.filter;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.CircuitBreakerConfig;
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

    @Test
    void testNoCircuitBreakerConfigPasses() {
        context.route(new RouteConfig("r1", "/**", null, "", null, null, null, null, null));
        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testAllowsRequestsInitially() {
        RouteConfig route = new RouteConfig("r1", "/**", null, "",
                null, null, new CircuitBreakerConfig(3, 1000), null, null);
        context.route(route);

        assertTrue(filter.filter(context).continueChain());
    }

    @Test
    void testOpensAfterThreshold() {
        RouteConfig route = new RouteConfig("r1", "/**", null, "",
                null, null, new CircuitBreakerConfig(3, 5000), null, null);
        context.route(route);

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
        RouteConfig route = new RouteConfig("r1", "/**", null, "",
                null, null, new CircuitBreakerConfig(2, 200), null, null);
        context.route(route);

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
        RouteConfig route = new RouteConfig("r1", "/**", null, "",
                null, null, new CircuitBreakerConfig(2, 100), null, null);
        context.route(route);

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
