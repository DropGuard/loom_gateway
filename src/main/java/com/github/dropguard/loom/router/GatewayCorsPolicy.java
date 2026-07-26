package com.github.dropguard.loom.router;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gateway-owned CORS handling. The framework's {@code quarkus.http.cors} does not apply to
 * {@code @Route} reactive routes, so the gateway writes the CORS response headers itself. Kept as a
 * standalone, testable unit (no Vert.x coupling in the decision logic) so the CORS contract --
 * origin allow-list, preflight short-circuit, and the rule that an unmatched origin is never echoed
 * back -- is locked by unit tests rather than only by integration tests.
 */
public class GatewayCorsPolicy {

    private final boolean enabled;
    private final Pattern originPattern;
    private final List<String> methods;
    private final List<String> headers;
    private final List<String> exposedHeaders;
    private final long maxAge;

    public GatewayCorsPolicy(
            boolean enabled,
            String origins,
            String methods,
            String headers,
            String exposedHeaders,
            long maxAge) {
        this.enabled = enabled;
        this.originPattern =
                (origins != null && !origins.isBlank())
                        ? Pattern.compile("*".equals(origins.trim()) ? ".*" : origins)
                        : null;
        this.methods = splitCsv(methods);
        this.headers = splitCsv(headers);
        this.exposedHeaders = splitCsv(exposedHeaders);
        this.maxAge = maxAge;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** A CORS request carries an Origin header. */
    public boolean isCorsRequest(String origin) {
        return enabled && origin != null;
    }

    /** A preflight is an OPTIONS request with Access-Control-Request-Method. */
    public boolean isPreflight(String origin, String method, boolean hasRequestMethod) {
        return isCorsRequest(origin) && "OPTIONS".equals(method) && hasRequestMethod;
    }

    /**
     * Builds the CORS response headers. The allow-origin header is only included when the request
     * Origin matches the configured allow-list; an unmatched origin yields no allow-origin entry,
     * so cross-origin access is not granted. The caller is responsible for writing these onto the
     * response (keeps this unit Vert.x-free and testable).
     */
    public Map<String, String> buildHeaders(String origin) {
        Map<String, String> out = new LinkedHashMap<>();
        if (originPattern != null && origin != null && originPattern.matcher(origin).matches()) {
            out.put("Access-Control-Allow-Origin", origin);
        }
        if (!methods.isEmpty()) {
            out.put("Access-Control-Allow-Methods", String.join(",", methods));
        }
        if (!headers.isEmpty()) {
            out.put("Access-Control-Allow-Headers", String.join(",", headers));
        }
        if (!exposedHeaders.isEmpty()) {
            out.put("Access-Control-Expose-Headers", String.join(",", exposedHeaders));
        }
        if (maxAge > 0) {
            out.put("Access-Control-Max-Age", Long.toString(maxAge));
        }
        return out;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
