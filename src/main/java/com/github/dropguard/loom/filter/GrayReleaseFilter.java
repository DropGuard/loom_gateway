package com.github.dropguard.loom.filter;

import com.github.dropguard.loom.model.RouteConfig.GrayConfig;
import com.github.dropguard.loom.util.ApiKeyHasher;

import jakarta.inject.Singleton;

import java.util.Map;

import org.jboss.logging.Logger;

@Singleton
public class GrayReleaseFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(GrayReleaseFilter.class);

    /**
     * Header a trusted layer (e.g. an upstream ingress/LB) sets to opt a request into the canary
     * under {@code mode: rule}. It is a dedicated header, distinct from the client-facing gray
     * header (X-Canary) that the gateway strips at ingress, so clients can never forge it.
     */
    public static final String TRUSTED_GRAY_HEADER = "X-Internal-Canary";

    @Override
    public void apply(FilterContext context, Next next) throws Exception {
        GrayConfig gray = context.filters().gray();
        if (gray == null || !gray.isEnabled()) {
            next.run();
            return;
        }

        if (gray.isPercentage()) {
            routeByPercentage(context, gray);
        } else if (gray.isRule()) {
            routeByRule(context, gray);
        }

        next.run();
    }

    private void routeByPercentage(FilterContext context, GrayConfig gray) {
        double percent = gray.effectivePercent();
        if (percent <= 0) {
            return;
        }
        if (shouldRouteToGray(context, percent)) {
            LOG.debugf("Gray (percentage): routing %.1f%% to %s", percent, gray.grayUpstream());
            context.targetUpstream(gray.grayUpstream());
        }
    }

    private void routeByRule(FilterContext context, GrayConfig gray) {
        // The trusted header survives ingress stripping (it is not in the
        // gateway.gray.headers strip list), so only a trusted layer that injects
        // it can drive this match. A client-supplied header with the same name
        // (e.g. X-Canary) was already removed upstream.
        String headerName = gray.ruleHeader() != null ? gray.ruleHeader() : TRUSTED_GRAY_HEADER;
        String actual = context.request().getHeader(headerName);
        if (gray.ruleValue() != null && gray.ruleValue().equals(actual)) {
            LOG.debugf(
                    "Gray (rule): matched trusted header %s=%s -> %s",
                    headerName, gray.ruleValue(), gray.grayUpstream());
            context.targetUpstream(gray.grayUpstream());
        }
    }

    private boolean shouldRouteToGray(FilterContext context, double threshold) {
        String identity = resolveIdentity(context);
        if (identity == null) {
            // DEFECT F1: percentage gray needs an authenticated identity
            // (JWT sub or API key). Unauthenticated requests can never be
            // bucketed, so they silently stay on the primary. Log it so the
            // behavior is observable rather than mysterious.
            LOG.debugf(
                    "Gray (percentage): no resolvable identity for route '%s' "
                            + "- request stays on primary (percentage canary requires auth)",
                    context.route() != null ? context.route().id() : "?");
            return false;
        }
        int bucket = Math.floorMod(identity.hashCode(), 100);
        return bucket < threshold;
    }

    private String resolveIdentity(FilterContext context) {
        Map<String, Object> claims = context.claims();
        if (claims != null) {
            Object sub = claims.get("sub");
            if (sub != null) {
                return sub.toString();
            }
        }
        String apiKey = context.request().getHeader("X-API-Key");
        if (apiKey != null) {
            return ApiKeyHasher.hash(apiKey);
        }
        return null;
    }
}
