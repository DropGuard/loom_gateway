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

    private boolean applyWithOutcome(FilterContext ctx, boolean outcome) throws Exception {
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        filter.apply(ctx, () -> {
            nextCalled.set(true);
            // Simulate the downstream proxy handler reporting the actual outcome,
            // which is now the single source of truth for the circuit breaker.
            String routeId = ctx.route() != null ? ctx.route().id() : "r1";
            filter.recordOutcome(routeId, outcome);
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
        assertTrue(applyWithOutcome(context, true));
    }

    @Test
    void testAllowsRequestsInitially() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(3, 1000)));
        assertTrue(applyWithOutcome(context, true));
    }

    @Test
    void testOpensAfterThreshold() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(3, 5000)));

        assertTrue(applyWithOutcome(context, false));

        FilterContext ctx2 = freshContext();
        assertTrue(applyWithOutcome(ctx2, false));

        FilterContext ctx3 = freshContext();
        assertTrue(applyWithOutcome(ctx3, false));

        FilterContext ctx4 = freshContext();
        assertFalse(applyWithOutcome(ctx4, true));
        assertEquals(503, ((MockResponse) ctx4.response()).statusCode);
    }

    @Test
    void testHalfOpenAfterTimeout() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(2, 200)));

        assertTrue(applyWithOutcome(context, false));

        FilterContext ctx2 = new FilterContext(request, new MockResponse());
        ctx2.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertTrue(applyWithOutcome(ctx2, false));

        FilterContext ctx3 = new FilterContext(request, new MockResponse());
        ctx3.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertFalse(applyWithOutcome(ctx3, true));

        Thread.sleep(250);

        FilterContext ctx4 = new FilterContext(request, new MockResponse());
        ctx4.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertTrue(applyWithOutcome(ctx4, true));

        FilterContext ctx5 = new FilterContext(request, new MockResponse());
        ctx5.route(routeWithCB(new CircuitBreakerConfig(2, 200)));
        assertTrue(applyWithOutcome(ctx5, true));
    }

    @Test
    void testHalfOpenBackToOpenOnFailure() throws Exception {
        context.route(routeWithCB(new CircuitBreakerConfig(2, 100)));

        assertTrue(applyWithOutcome(context, false));

        FilterContext ctx2 = new FilterContext(request, new MockResponse());
        ctx2.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertTrue(applyWithOutcome(ctx2, false));

        FilterContext ctx3 = new FilterContext(request, new MockResponse());
        ctx3.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertFalse(applyWithOutcome(ctx3, true));

        Thread.sleep(150);

        FilterContext ctx4 = new FilterContext(request, new MockResponse());
        ctx4.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertTrue(applyWithOutcome(ctx4, false));

        FilterContext ctx5 = new FilterContext(request, new MockResponse());
        ctx5.route(routeWithCB(new CircuitBreakerConfig(2, 100)));
        assertFalse(applyWithOutcome(ctx5, true));
    }

    /**
     * Regression for DEFECT #7: a steady trickle of failures must NOT keep the
     * breaker stuck OPEN forever. The OPEN timer is armed only on the
     * CLOSED -> OPEN transition, so even if failures keep arriving while OPEN,
     * the HALF_OPEN deadline is still reached and traffic is allowed again.
     * Uses a dedicated breaker instance + routeId so it cannot interfere with
     * the shared "r1" breaker used by the other tests.
     */
    @Test
    void breakerRecoversFromSustainedLowRateFailures() throws Exception {
        CircuitBreakerFilter isolated = new CircuitBreakerFilter();
        CircuitBreakerConfig cb = new CircuitBreakerConfig(3, 200); // threshold 3, timeout 200ms

        RouteConfig route = new RouteConfig("r-lowrate", "/**", null, "",
                FilterConfig.builder().circuitBreaker(cb).build());

        // Trip OPEN with 3 consecutive failures (breaker is CLOSED for the first
        // two, transitions to OPEN on the third — so we don't assert per-request).
        for (int i = 0; i < 3; i++) {
            FilterContext ctx = new FilterContext(request, new MockResponse());
            ctx.route(route);
            isolated.apply(ctx, () -> isolated.recordOutcome("r-lowrate", false));
        }

        // Now OPEN: the next request must be blocked.
        FilterContext blocked = new FilterContext(request, new MockResponse());
        blocked.route(route);
        AtomicBoolean blockedNext = new AtomicBoolean(false);
        isolated.apply(blocked, () -> blockedNext.set(true));
        assertFalse(blockedNext.get(), "request after threshold must be blocked (OPEN)");

        // Wait past the timeout, then keep failing *while OPEN* (the bug case):
        // a failure every 50ms for ~400ms must NOT push the deadline forward.
        Thread.sleep(250);
        for (int i = 0; i < 8; i++) {
            FilterContext ctx = new FilterContext(request, new MockResponse());
            ctx.route(route);
            isolated.apply(ctx, () -> isolated.recordOutcome("r-lowrate", false));
            Thread.sleep(50);
        }

        // After another timeout window the breaker must reach HALF_OPEN and let
        // a request through (next called, response untouched at 200).
        Thread.sleep(250);
        FilterContext ctx = new FilterContext(request, new MockResponse());
        ctx.route(route);
        AtomicBoolean recovered = new AtomicBoolean(false);
        isolated.apply(ctx, () -> {
            recovered.set(true);
            isolated.recordOutcome("r-lowrate", true);
        });
        assertTrue(recovered.get(), "breaker must recover (HALF_OPEN) and pass traffic");
        assertEquals(200, ((MockResponse) ctx.response()).statusCode);
    }
}
