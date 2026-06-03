package com.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.CircuitBreakerConfig;
import com.gateway.model.RouteConfig.FilterConfig;
import com.gateway.testutil.MockRequest;
import com.gateway.testutil.MockResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CircuitBreakerFilterTest {

    private CircuitBreakerFilter filter;
    private MockRequest request;
    private MockResponse response;
    private FilterContext context;

    @BeforeEach
    void setUp() {
        filter = new CircuitBreakerFilter();
        request = new MockRequest();
        response = new MockResponse();
        context = new FilterContext(request, response);
    }

    private boolean applyWithProxyResult(FilterContext ctx, boolean proxySuccess) throws Exception {
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        filter.apply(ctx, () -> {
            nextCalled.set(true);
            ctx.proxySuccess(proxySuccess);
        });
        return nextCalled.get();
    }

    private RouteConfig routeWithCB(CircuitBreakerConfig cb) {
        return new RouteConfig("r1", "/**", null, "", FilterConfig.builder().circuitBreaker(cb).build());
    }

    private FilterContext freshContext() {
        MockResponse resp = new MockResponse();
        FilterContext ctx = new FilterContext(request, resp);
        ctx.route(routeWithCB(new CircuitBreakerConfig(3, 5000)));
        return ctx;
    }

    @Test
    void testNoCircuitBreakerConfigPasses() throws Exception {
        context.route(new RouteConfig("r1", "/**", null, "", null));
        assertTrue(applyWithProxyResult(context, true));
    }

    @Test
    void testAllowsRequestsInitially() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(3, 1000)));
        assertTrue(applyWithProxyResult(context, true));
    }

    @Test
    void testOpensAfterThreshold() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(3, 5000)));

        assertTrue(applyWithProxyResult(context, false));

        FilterContext ctx2 = freshContext();
        assertTrue(applyWithProxyResult(ctx2, false));

        FilterContext ctx3 = freshContext();
        assertTrue(applyWithProxyResult(ctx3, false));

        FilterContext ctx4 = freshContext();
        assertFalse(applyWithProxyResult(ctx4, true));
        assertEquals(503, ((MockResponse) ctx4.response()).statusCode);
    }

    @Test
    void testHalfOpenAfterTimeout() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(2, 200)));

        assertTrue(applyWithProxyResult(context, false));

        FilterContext ctx2 = new FilterContext(request, new MockResponse());
        ctx2.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertTrue(applyWithProxyResult(ctx2, false));

        FilterContext ctx3 = new FilterContext(request, new MockResponse());
        ctx3.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertFalse(applyWithProxyResult(ctx3, true));

        Thread.sleep(250);

        FilterContext ctx4 = new FilterContext(request, new MockResponse());
        ctx4.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertTrue(applyWithProxyResult(ctx4, true));

        FilterContext ctx5 = new FilterContext(request, new MockResponse());
        ctx5.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertTrue(applyWithProxyResult(ctx5, true));
    }

    @Test
    void testHalfOpenBackToOpenOnFailure() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(2, 100)));

        assertTrue(applyWithProxyResult(context, false));

        FilterContext ctx2 = new FilterContext(request, new MockResponse());
        ctx2.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertTrue(applyWithProxyResult(ctx2, false));

        FilterContext ctx3 = new FilterContext(request, new MockResponse());
        ctx3.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertFalse(applyWithProxyResult(ctx3, true));

        Thread.sleep(150);

        FilterContext ctx4 = new FilterContext(request, new MockResponse());
        ctx4.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertTrue(applyWithProxyResult(ctx4, false));

        FilterContext ctx5 = new FilterContext(request, new MockResponse());
        ctx5.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertFalse(applyWithProxyResult(ctx5, true));
    }
}
