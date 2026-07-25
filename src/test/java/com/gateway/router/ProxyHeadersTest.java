package com.gateway.router;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vertx.core.MultiMap;

/**
 * Zero-mock unit tests for ProxyHeaders: the header transformations that used
 * to be buried inside HttpProxyHandler.proxy() behind a 7-layer Vert.x mock.
 * These assert the pure mapping directly.
 */
class ProxyHeadersTest {

    private List<Map.Entry<String, String>> entriesOf(MultiMap mm) {
        return mm.entries();
    }

    @Test
    void buildForwardHeaders_stripsHopByHop_contentLength_andIncomingUser() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Authorization", "Bearer tok");
        incoming.add("Connection", "keep-alive");
        incoming.add("Upgrade", "websocket");
        incoming.add("Content-Length", "123");
        incoming.add("X-User-Id", "spoofed"); // must be dropped, re-injected from claims
        incoming.add("X-Request-Id", "abc");

        Map<String, String> out = ProxyHeaders.buildForwardHeaders(entriesOf(incoming), null);

        assertEquals("Bearer tok", out.get("Authorization"));
        assertEquals("abc", out.get("X-Request-Id"));
        assertNull(out.get("Connection"));
        assertNull(out.get("Upgrade"));
        assertNull(out.get("Content-Length"));
        assertNull(out.get("X-User-Id"));
    }

    @Test
    void buildForwardHeaders_injectsUserFromSubClaim() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Accept", "application/json");

        Map<String, String> out = ProxyHeaders.buildForwardHeaders(entriesOf(incoming), "user-42");

        assertEquals("user-42", out.get("X-User-Id"));
        assertEquals("application/json", out.get("Accept"));
    }

    @Test
    void buildForwardHeaders_emptySubDoesNotInjectUser() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Accept", "application/json");

        Map<String, String> out = ProxyHeaders.buildForwardHeaders(entriesOf(incoming), "");

        assertNull(out.get("X-User-Id"));
    }

    @Test
    void selectClientHeaders_stripsHopByHopFromUpstream() {
        MultiMap upstream = MultiMap.caseInsensitiveMultiMap();
        upstream.add("Content-Type", "application/json");
        upstream.add("Connection", "keep-alive"); // hop-by-hop, dropped
        upstream.add("Transfer-Encoding", "chunked"); // hop-by-hop, dropped
        upstream.add("X-Custom", "v");

        Map<String, String> out = ProxyHeaders.selectClientHeaders(entriesOf(upstream));

        assertEquals("application/json", out.get("Content-Type"));
        assertEquals("v", out.get("X-Custom"));
        assertNull(out.get("Connection"));
        assertNull(out.get("Transfer-Encoding"));
    }

    @Test
    void isHopByHop_isCaseInsensitiveAndExact() {
        assertTrue(ProxyHeaders.isHopByHop("Connection"));
        assertTrue(ProxyHeaders.isHopByHop("connection"));
        assertTrue(ProxyHeaders.isHopByHop("UPGRADE"));
        assertFalse(ProxyHeaders.isHopByHop("Authorization"));
        assertFalse(ProxyHeaders.isHopByHop("X-User-Id"));
    }
}
