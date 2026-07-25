package com.gateway.router;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure header-mapping logic for HTTP proxying, deliberately free of any Vert.x
 * request/response coupling so it is unit-testable without mocking the HTTP
 * stack. HttpProxyHandler delegates the two header transformations here:
 *  - buildForwardHeaders: what the gateway sends upstream (incoming headers
 *    minus hop-by-hop / content-length / an incoming X-User-Id, plus the
 *    authenticated subject re-injected as X-User-Id).
 *  - selectClientHeaders: what the gateway copies from the upstream response
 *    to the client (upstream headers minus hop-by-hop).
 */
final class ProxyHeaders {

    static final List<String> HOP_BY_HOP = List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host");

    private ProxyHeaders() {
    }

    static boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase());
    }

    static Map<String, String> buildForwardHeaders(
            List<Map.Entry<String, String>> incoming, String subClaim) {
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
        if (subClaim != null && !subClaim.isEmpty()) {
            out.put("X-User-Id", subClaim);
        }
        return out;
    }

    static Map<String, String> selectClientHeaders(
            List<Map.Entry<String, String>> upstream) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> entry : upstream) {
            if (!isHopByHop(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}
