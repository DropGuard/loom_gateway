package com.github.dropguard.loom.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.model.RouteConfig;
import com.github.dropguard.loom.model.RouteConfig.AuthConfig;
import com.github.dropguard.loom.model.RouteConfig.FilterConfig;
import com.github.dropguard.loom.model.RouteConfig.GrayConfig;
import com.github.dropguard.loom.testutil.MockRequest;
import com.github.dropguard.loom.util.ApiKeyHasher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrayReleaseFilterTest {

    private GrayReleaseFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GrayReleaseFilter();
    }

    private RouteConfig grayRoutePercentage(double percent) {
        return new RouteConfig(
                "r1",
                "/**",
                null,
                "backend:8080",
                FilterConfig.builder()
                        // API-key auth means X-API-Key supplied by a client is validated by
                        // AuthFilter before it is trusted as a canary bucket identity.
                        .auth(new AuthConfig("api-key", true, null, List.of("test-key")))
                        .gray(
                                new GrayConfig(
                                        "percentage", "gray-backend:8080", percent, null, null))
                        .build());
    }

    private RouteConfig grayRoutePercentageWithoutAuth(double percent) {
        return new RouteConfig(
                "r1",
                "/**",
                null,
                "backend:8080",
                FilterConfig.builder()
                        .gray(
                                new GrayConfig(
                                        "percentage", "gray-backend:8080", percent, null, null))
                        .build());
    }

    private RouteConfig grayRouteRule() {
        return new RouteConfig(
                "r1",
                "/**",
                null,
                "backend:8080",
                FilterConfig.builder()
                        .gray(
                                new GrayConfig(
                                        "rule",
                                        "gray-backend:8080",
                                        null,
                                        GrayReleaseFilter.TRUSTED_GRAY_HEADER,
                                        "true"))
                        .build());
    }

    private FilterContext context(String sub, String apiKey, double percent) {
        MockRequest req = new MockRequest();
        if (apiKey != null) {
            req.headers.put("X-API-Key", apiKey);
        }
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRoutePercentage(percent));
        if (sub != null) {
            ctx.claims(Map.of("sub", (Object) sub));
        }
        return ctx;
    }

    private FilterContext contextWithJwtSub(String sub) {
        return context(sub, null, 30);
    }

    private FilterContext contextWithApiKey(String key) {
        return context(null, key, 30);
    }

    private void applyFilter(FilterContext ctx) throws Exception {
        filter.apply(ctx, () -> {});
    }

    private boolean isGray(FilterContext ctx) throws Exception {
        applyFilter(ctx);
        return "gray-backend:8080".equals(ctx.effectiveUpstream());
    }

    // ---- No gray config ----

    @Test
    void noGrayConfig_passesThrough() throws Exception {
        FilterContext ctx = new FilterContext(new MockRequest(), null);
        ctx.route(new RouteConfig("r1", "/**", null, "backend:8080", null));
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        filter.apply(ctx, () -> nextCalled.set(true));

        assertTrue(nextCalled.get());
        assertEquals("backend:8080", ctx.effectiveUpstream());
    }

    // ---- Header match (trusted dedicated header only; client headers ignored) ----

    @Test
    void ruleGray_trustedHeaderRoutesToCanary() throws Exception {
        MockRequest req = new MockRequest();
        req.headers.put(GrayReleaseFilter.TRUSTED_GRAY_HEADER, "true");
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRouteRule());

        applyFilter(ctx);
        assertEquals("gray-backend:8080", ctx.effectiveUpstream());
    }

    @Test
    void ruleGray_clientFacingHeaderIsIgnored() throws Exception {
        MockRequest req = new MockRequest();
        req.headers.put("X-Canary", "true");
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRouteRule());

        applyFilter(ctx);
        assertEquals("backend:8080", ctx.effectiveUpstream());
    }

    @Test
    void ruleGray_wrongValueStaysOnPrimary() throws Exception {
        MockRequest req = new MockRequest();
        req.headers.put(GrayReleaseFilter.TRUSTED_GRAY_HEADER, "false");
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRouteRule());

        applyFilter(ctx);
        assertEquals("backend:8080", ctx.effectiveUpstream());
    }

    // ---- Same identity -> same bucket, for both identity sources ----

    @Test
    void sameIdentity_alwaysSameDecision() throws Exception {
        assertSameDecision(() -> contextWithJwtSub("user-42"));
        assertSameDecision(() -> contextWithApiKey("pk-stable-key"));
    }

    private void assertSameDecision(Supplier<FilterContext> contexts) throws Exception {
        boolean first = isGray(contexts.get());
        for (int i = 0; i < 20; i++) {
            assertEquals(
                    first,
                    isGray(contexts.get()),
                    "same identity must always produce the same routing decision");
        }
    }

    // ---- No identity -> never gray ----

    @Test
    void noIdentity_staysOnPrimary() throws Exception {
        FilterContext ctx = new FilterContext(new MockRequest(), null);
        ctx.route(grayRoutePercentage(100));

        applyFilter(ctx);
        assertEquals(
                "backend:8080",
                ctx.effectiveUpstream(),
                "requests without identity must never route to gray");
    }

    @Test
    void forgedApiKeyOnUnauthenticatedRoute_staysOnPrimary() throws Exception {
        // Without API-key auth the header is client-controlled, so it must not
        // be usable to self-select the canary.
        MockRequest req = new MockRequest();
        req.headers.put("X-API-Key", "forged-key");
        FilterContext ctx = new FilterContext(req, null);
        ctx.route(grayRoutePercentageWithoutAuth(100));

        applyFilter(ctx);
        assertEquals(
                "backend:8080",
                ctx.effectiveUpstream(),
                "unvalidated X-API-Key must not act as a canary identity");
    }

    // ---- Percentage boundaries ----

    @Test
    void percentageBoundaries_respectBucketRange() throws Exception {
        assertEquals("backend:8080", upstreamAt(0));
        assertEquals("gray-backend:8080", upstreamAt(100));
    }

    private String upstreamAt(double percent) throws Exception {
        FilterContext ctx = context("any-user", null, percent);
        applyFilter(ctx);
        return ctx.effectiveUpstream();
    }

    // ---- Distribution: many identities approximate the configured percent ----

    @Test
    void distributionApproximatesConfiguredPercent() throws Exception {
        assertDistribution(30.0, i -> context("user-" + i, null, 30.0));
        assertDistribution(50.0, i -> context(null, "key-" + i, 50.0));
    }

    private void assertDistribution(double percent, IntFunction<FilterContext> contexts)
            throws Exception {
        int grayCount = 0;
        int total = 1000;

        for (int i = 0; i < total; i++) {
            if (isGray(contexts.apply(i))) {
                grayCount++;
            }
        }

        double actual = (double) grayCount / total * 100;
        assertTrue(
                Math.abs(actual - percent) <= 5,
                String.format("Expected ~%.0f%% gray, got %.1f%%", percent, actual));
    }

    // ---- JWT sub takes priority over API key ----

    @Test
    void jwtSubject_takesPriorityOverApiKey() throws Exception {
        String sub = "user-jwt";
        String key = apiKeyWithDifferentBucket(sub, 50);

        boolean jwtOnly = isGray(context(sub, null, 50));
        boolean keyOnly = isGray(context(null, key, 50));
        boolean both = isGray(context(sub, key, 50));

        assertNotEquals(jwtOnly, keyOnly, "test identities must fall on opposite sides");
        assertEquals(jwtOnly, both, "JWT sub must win when both credentials are present");
    }

    private static String apiKeyWithDifferentBucket(String sub, double percent) {
        boolean subGray = Math.floorMod(sub.hashCode(), 100) < percent;
        for (int i = 0; ; i++) {
            String key = "key-" + i;
            boolean keyGray = Math.floorMod(ApiKeyHasher.hash(key).hashCode(), 100) < percent;
            if (keyGray != subGray) {
                return key;
            }
        }
    }
}
