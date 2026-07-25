package com.gateway.router;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Locks the gateway-owned CORS contract -- the logic behind the previously
 * broken CORS behavior (framework quarkus.http.cors does not apply to @Route
 * reactive routes). Covers origin allow-list matching, preflight detection,
 * header construction, and the critical rule that an unmatched origin is never
 * echoed back. No Mockito dependency (offline); the policy is Vert.x-free.
 */
class GatewayCorsPolicyTest {

    private GatewayCorsPolicy policy() {
        return new GatewayCorsPolicy(true, ".*", "GET,POST,OPTIONS",
                "Authorization,Content-Type", "X-Request-Id", 86400);
    }

    @Test
    void enabledPolicy_acceptsAnyOrigin() {
        GatewayCorsPolicy p = policy();
        assertTrue(p.isCorsRequest("http://example.com"));
        assertFalse(p.isPreflight("http://example.com", "GET", false));
    }

    @Test
    void disabledPolicy_ignoresCors() {
        GatewayCorsPolicy p = new GatewayCorsPolicy(false, ".*", "GET", "Authorization", "", 0);
        assertFalse(p.isCorsRequest("http://example.com"));
        assertFalse(p.isPreflight("http://example.com", "OPTIONS", true));
    }

    @Test
    void preflightDetected_onOptionsWithRequestMethod() {
        GatewayCorsPolicy p = policy();
        assertTrue(p.isPreflight("http://example.com", "OPTIONS", true));
    }

    @Test
    void plainOptionsWithoutRequestMethod_isNotPreflight() {
        GatewayCorsPolicy p = policy();
        assertFalse(p.isPreflight("http://example.com", "OPTIONS", false));
        assertTrue(p.isCorsRequest("http://example.com"));
    }

    @Test
    void noOrigin_isNeitherCorsNorPreflight() {
        GatewayCorsPolicy p = policy();
        assertFalse(p.isCorsRequest(null));
        assertFalse(p.isPreflight(null, "OPTIONS", true));
    }

    @Test
    void buildHeaders_includesAllowOriginAndOthers() {
        GatewayCorsPolicy p = policy();
        Map<String, String> headers = p.buildHeaders("http://example.com");

        assertEquals("http://example.com", headers.get("Access-Control-Allow-Origin"));
        assertEquals("GET,POST,OPTIONS", headers.get("Access-Control-Allow-Methods"));
        assertEquals("Authorization,Content-Type", headers.get("Access-Control-Allow-Headers"));
        assertEquals("X-Request-Id", headers.get("Access-Control-Expose-Headers"));
        assertEquals("86400", headers.get("Access-Control-Max-Age"));
    }

    @Test
    void buildHeaders_omitsAllowOrigin_whenOriginNotAllowed() {
        // Allow-list restricted to a specific host; a foreign origin must NOT
        // be echoed back (no cross-origin grant), but other config headers
        // are still emitted.
        GatewayCorsPolicy p = new GatewayCorsPolicy(true, "https://trusted\\.com",
                "GET", "Authorization", "", 0);
        Map<String, String> headers = p.buildHeaders("http://evil.com");

        assertNull(headers.get("Access-Control-Allow-Origin"));
        assertEquals("GET", headers.get("Access-Control-Allow-Methods"));
    }

    @Test
    void wildcardOriginsConfig_expandsToMatchAll() {
        GatewayCorsPolicy p = new GatewayCorsPolicy(true, "*", "GET", "", "", 0);
        Map<String, String> headers = p.buildHeaders("http://anything.test");
        assertEquals("http://anything.test", headers.get("Access-Control-Allow-Origin"));
    }
}
