package com.github.dropguard.loom.router;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure header-mapping logic for HTTP proxying, deliberately free of any Vert.x request/response
 * coupling so it is unit-testable without mocking the HTTP stack. HttpProxyHandler delegates the
 * two header transformations here: - buildForwardHeaders: what the gateway sends upstream (incoming
 * headers minus hop-by-hop / content-length / an incoming X-User-Id, plus the authenticated subject
 * re-injected as X-User-Id). - selectClientHeaders: what the gateway copies from the upstream
 * response to the client (upstream headers minus hop-by-hop).
 */
final class ProxyHeaders {

    static final java.util.Set<String> HOP_BY_HOP =
            java.util.Set.of(
                    "connection",
                    "keep-alive",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailers",
                    "transfer-encoding",
                    "upgrade",
                    "host");

    private ProxyHeaders() {}

    static boolean isHopByHop(String name) {
        return name != null && HOP_BY_HOP.contains(name.toLowerCase());
    }

    static Map<String, String> buildForwardHeaders(
            List<Map.Entry<String, String>> incoming,
            Map<String, Object> claims,
            List<String> forwardClaims) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> entry : incoming) {
            String key = entry.getKey();
            if (isHopByHop(key)
                    || "content-length".equalsIgnoreCase(key)
                    || "X-User-Id".equalsIgnoreCase(key)) {
                continue;
            }
            out.put(key, entry.getValue());
        }
        // Mirror the SCG ClaimsForward filter: forward configured claims to the
        // upstream as headers. "sub" -> X-User-Id (the canonical identity header);
        // any other claim -> X-Claim-<claim>.
        if (claims != null && !claims.isEmpty()) {
            List<String> which =
                    (forwardClaims != null && !forwardClaims.isEmpty())
                            ? forwardClaims
                            : List.of("sub");
            for (String claim : which) {
                Object value = claims.get(claim);
                if (value == null) {
                    continue;
                }
                String header = "sub".equalsIgnoreCase(claim) ? "X-User-Id" : "X-Claim-" + claim;
                out.put(header, value.toString());
            }
        }
        return out;
    }

    static Map<String, String> selectClientHeaders(List<Map.Entry<String, String>> upstream) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> entry : upstream) {
            if (!isHopByHop(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}
