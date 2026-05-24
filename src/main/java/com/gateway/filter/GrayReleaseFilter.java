package com.gateway.filter;

import com.gateway.model.RouteConfig.GrayConfig;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Map;

@Singleton
public class GrayReleaseFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(GrayReleaseFilter.class);

    @Override
    public FilterResult filter(FilterContext context) {
        GrayConfig gray = context.filters().gray();
        if (gray == null || gray.grayUpstream() == null) {
            return FilterResult.CONTINUE;
        }

        if (matchByHeader(context, gray)) {
            return FilterResult.CONTINUE;
        }

        double threshold = Math.min(Math.max(gray.trafficPercent(), 0), 100);
        if (threshold > 0 && shouldRouteToGray(context, threshold)) {
            LOG.debugf("Gray matched by traffic percent (%.1f%%)", threshold);
            context.targetUpstream(gray.grayUpstream());
        }

        return FilterResult.CONTINUE;
    }

    private boolean matchByHeader(FilterContext context, GrayConfig gray) {
        if (gray.headerMatch() == null || gray.headerMatch().isEmpty()) {
            return false;
        }
        for (String headerPattern : gray.headerMatch()) {
            String[] parts = headerPattern.split(":", 2);
            if (parts.length == 2) {
                String name = parts[0].trim();
                String value = parts[1].trim();
                String reqValue = context.request().getHeader(name);
                if (value.equals(reqValue)) {
                    LOG.debugf("Gray matched by header '%s=%s'", name, value);
                    context.targetUpstream(gray.grayUpstream());
                    return true;
                }
            }
        }
        return false;
    }

    private boolean shouldRouteToGray(FilterContext context, double threshold) {
        String identity = resolveIdentity(context);
        if (identity == null) {
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
            return apiKey;
        }
        return null;
    }
}
