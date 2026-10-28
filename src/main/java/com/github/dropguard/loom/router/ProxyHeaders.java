package com.github.dropguard.loom.router;

import com.github.dropguard.loom.util.ApiKeyHasher;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure header-mapping logic for HTTP proxying, deliberately free of any Vert.x request/response
 * coupling so it is unit-testable without mocking the HTTP stack. HttpProxyHandler delegates the
 * two header transformations here:
 *
 * <ul>
 *   <li>{@link #buildForwardHeaders}: what the gateway sends upstream. On authenticated routes the
 *       gateway terminates auth and strips {@code Authorization}/{@code X-API-Key}; client-supplied
 *       {@code X-User-Id}/{@code X-Claim-*} are always dropped, then the verified identity and
 *       configured claims are injected.
 *   <li>{@link #selectClientHeaders}: what the gateway copies from the upstream response to the
 *       client (upstream headers minus hop-by-hop).
 * </ul>
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
        return name != null && HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    static Map<String, String> buildForwardHeaders(
            List<Map.Entry<String, String>> incoming,
            Map<String, Object> claims,
            List<String> forwardClaims,
            String enforcedAuthType,
            String apiKey) {
        boolean authenticated =
                "jwt".equals(enforcedAuthType) || "api-key".equals(enforcedAuthType);
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> entry : incoming) {
            String key = entry.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (isHopByHop(key)
                    || "content-length".equals(lower)
                    || "x-user-id".equals(lower)
                    || lower.startsWith("x-claim-")
                    || (authenticated
                            && ("authorization".equals(lower) || "x-api-key".equals(lower)))) {
                continue;
            }
            out.put(key, entry.getValue());
        }

        String identity = identityFor(claims, enforcedAuthType, apiKey);
        if (identity != null) {
            out.put("X-User-Id", identity);
        }

        // Mirror the SCG ClaimsForward filter: expose configured claims to the
        // upstream. "sub" already became X-User-Id above; other claims become
        // X-Claim-<claim>.
        if (claims != null && !claims.isEmpty()) {
            List<String> which =
                    (forwardClaims != null && !forwardClaims.isEmpty())
                            ? forwardClaims
                            : List.of("sub");
            for (String claim : which) {
                if ("sub".equalsIgnoreCase(claim)) {
                    continue;
                }
                Object value = claims.get(claim);
                if (value != null) {
                    out.put("X-Claim-" + claim, value.toString());
                }
            }
        }
        return out;
    }

    /**
     * Canonical downstream identity: the JWT {@code sub} when present, otherwise the SHA-256 of a
     * validated API key. Returns {@code null} for unauthenticated routes.
     */
    static String identityFor(Map<String, Object> claims, String enforcedAuthType, String apiKey) {
        if (claims != null) {
            Object sub = claims.get("sub");
            if (sub != null) {
                return sub.toString();
            }
        }
        if ("api-key".equals(enforcedAuthType) && apiKey != null && !apiKey.isEmpty()) {
            return ApiKeyHasher.hash(apiKey);
        }
        return null;
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
