package com.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.FilterConfig;
import com.gateway.model.RouteConfig.GrayConfig;
import com.gateway.testutil.MockRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrayReleaseFilterTest {

    private GrayReleaseFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GrayReleaseFilter();
    }

    private RouteConfig grayRoute(double percent, List<String> headerMatch) {
        return new RouteConfig("r1", "/**", null, "backend:8080",
                FilterConfig.builder()
                        .gray(new GrayConfig(percent, "gray-backend:8080", headerMatch))
                        .build());
    }

    private FilterContext contextWithJwtSub(String sub) {
        MockRequest req = new MockRequest();
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRoute(30, null));
        ctx.claims(Map.of("sub", (Object) sub));
        return ctx;
    }

    private FilterContext contextWithApiKey(String key) {
        MockRequest req = new MockRequest();
        req.headers.put("X-API-Key", key);
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRoute(30, null));
        return ctx;
    }

    private void applyFilter(FilterContext ctx) throws Exception {
        filter.apply(ctx, () -> {
        });
    }

    // ---- No gray config ----

    @Test
    void noGrayConfig_continues() throws Exception {
        FilterContext ctx = new FilterContext(new MockRequest(), null);
        ctx.route(new RouteConfig("r1", "/**", null, "backend:8080", null));
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        filter.apply(ctx, () -> nextCalled.set(true));
        assertTrue(nextCalled.get());
        assertEquals("backend:8080", ctx.effectiveUpstream());
    }

    // ---- Header match ----

    @Test
    void headerMatch_routesToGray() throws Exception {
        MockRequest req = new MockRequest();
        req.headers.put("X-Canary", "true");
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRoute(0, List.of("X-Canary:true")));

        applyFilter(ctx);
        assertEquals("gray-backend:8080", ctx.effectiveUpstream());
    }

    @Test
    void headerMismatch_staysOnPrimary() throws Exception {
        MockRequest req = new MockRequest();
        req.headers.put("X-Canary", "false");
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRoute(0, List.of("X-Canary:true")));

        applyFilter(ctx);
        assertEquals("backend:8080", ctx.effectiveUpstream());
    }

    // ---- Deterministic: same identity -> same result ----

    @Test
    void sameJwtSub_alwaysSameDecision() throws Exception {
        boolean first = isGray(contextWithJwtSub("user-42"));
        for (int i = 0; i < 100; i++) {
            assertEquals(first, isGray(contextWithJwtSub("user-42")),
                    "Same identity must always produce the same routing decision");
        }
    }

    @Test
    void sameApiKey_alwaysSameDecision() throws Exception {
        boolean first = isGray(contextWithApiKey("pk-stable-key"));
        for (int i = 0; i < 100; i++) {
            assertEquals(first, isGray(contextWithApiKey("pk-stable-key")),
                    "Same API key must always produce the same routing decision");
        }
    }

    // ---- No identity -> never gray ----

    @Test
    void noIdentity_neverRoutesToGray() throws Exception {
        RouteConfig route = grayRoute(100, null);
        for (int i = 0; i < 100; i++) {
            FilterContext ctx = new FilterContext(new MockRequest(), null);
            ctx.route(route);
            applyFilter(ctx);
            assertEquals("backend:8080", ctx.effectiveUpstream(),
                    "Requests without identity must never route to gray");
        }
    }

    // ---- Distribution: many identities approximate the configured percent ----

    @Test
    void jwtSub_distributionMatchesPercent() throws Exception {
        double percent = 30.0;
        RouteConfig route = grayRoute(percent, null);
        int grayCount = 0;
        int total = 1000;

        for (int i = 0; i < total; i++) {
            FilterContext ctx = new FilterContext(new MockRequest(), null);
            ctx.route(route);
            ctx.claims(Map.of("sub", (Object) ("user-" + i)));
            applyFilter(ctx);
            if ("gray-backend:8080".equals(ctx.effectiveUpstream())) {
                grayCount++;
            }
        }

        double actual = (double) grayCount / total * 100;
        assertTrue(Math.abs(actual - percent) <= 5,
                String.format("Expected ~%.0f%% gray, got %.1f%%", percent, actual));
    }

    @Test
    void apiKey_distributionMatchesPercent() throws Exception {
        double percent = 50.0;
        RouteConfig route = grayRoute(percent, null);
        int grayCount = 0;
        int total = 1000;

        for (int i = 0; i < total; i++) {
            MockRequest req = new MockRequest();
            req.headers.put("X-API-Key", "key-" + i);
            FilterContext ctx = new FilterContext(req, null);
            ctx.route(route);
            applyFilter(ctx);
            if ("gray-backend:8080".equals(ctx.effectiveUpstream())) {
                grayCount++;
            }
        }

        double actual = (double) grayCount / total * 100;
        assertTrue(Math.abs(actual - percent) <= 5,
                String.format("Expected ~%.0f%% gray, got %.1f%%", percent, actual));
    }

    // ---- JWT sub takes priority over API Key ----

    @Test
    void jwtSubTakesPriorityOverApiKey() throws Exception {
        MockRequest req = new MockRequest();
        req.headers.put("X-API-Key", "some-key");
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRoute(100, null));
        ctx.claims(Map.of("sub", (Object) "user-jwt"));

        applyFilter(ctx);
        boolean withJwt = "gray-backend:8080".equals(ctx.effectiveUpstream());

        MockRequest req2 = new MockRequest();
        req2.headers.put("X-API-Key", "some-key");
        FilterContext ctx2 = new FilterContext(req2, null);
        ctx2.route(grayRoute(100, null));
        ctx2.claims(Map.of("sub", (Object) "user-jwt"));

        applyFilter(ctx2);
        boolean withJwt2 = "gray-backend:8080".equals(ctx2.effectiveUpstream());

        assertEquals(withJwt, withJwt2, "JWT sub should be the identity when both are present");
    }

    private boolean isGray(FilterContext ctx) throws Exception {
        applyFilter(ctx);
        return "gray-backend:8080".equals(ctx.effectiveUpstream());
    }
}
