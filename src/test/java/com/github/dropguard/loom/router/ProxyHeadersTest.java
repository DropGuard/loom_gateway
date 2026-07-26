package com.github.dropguard.loom.router;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.MultiMap;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

/**
 * Zero-mock unit tests for ProxyHeaders: the header transformations that used to be buried inside
 * HttpProxyHandler.proxy() behind a 7-layer Vert.x mock. These assert the pure mapping directly.
 */
class ProxyHeadersTest {

    @Test
    void buildForwardHeaders_stripsHopByHop_contentLength_andIncomingUser() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Authorization", "Bearer tok");
        incoming.add("Connection", "keep-alive");
        incoming.add("Upgrade", "websocket");
        incoming.add("Content-Length", "123");
        incoming.add("X-User-Id", "spoofed"); // must be dropped, re-injected from claims
        incoming.add("X-Request-Id", "abc");

        Map<String, String> out = ProxyHeaders.buildForwardHeaders(entriesOf(incoming), null, null);

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

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), Map.of("sub", "user-42"), List.of("sub"));

        assertEquals("user-42", out.get("X-User-Id"));
        assertEquals("application/json", out.get("Accept"));
    }

    @Test
    void buildForwardHeaders_emptySubDoesNotInjectUser() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Accept", "application/json");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(entriesOf(incoming), Map.of(), List.of("sub"));

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

    @Test
    void buildForwardHeaders_forwardsSubAsXUserId() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        Map<String, Object> claims = Map.of("sub", "user-7", "scope", "read");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(entriesOf(incoming), claims, List.of("sub"));

        assertEquals("user-7", out.get("X-User-Id"));
        assertNull(out.get("X-Claim-scope")); // not in the forward list
    }

    @Test
    void buildForwardHeaders_forwardsAdditionalClaimsAsXClaimPrefixed() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        Map<String, Object> claims = Map.of("sub", "user-7", "scope", "read", "tenant", "acme");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), claims, List.of("sub", "scope", "tenant"));

        assertEquals("user-7", out.get("X-User-Id"));
        assertEquals("read", out.get("X-Claim-scope"));
        assertEquals("acme", out.get("X-Claim-tenant"));
    }

    @Test
    void buildForwardHeaders_missingClaimIsSkipped() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        Map<String, Object> claims = Map.of("sub", "user-7");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), claims, List.of("sub", "scope"));

        assertEquals("user-7", out.get("X-User-Id"));
        assertNull(out.get("X-Claim-scope")); // absent from claims -> not injected
    }

    @Test
    void buildForwardHeaders_nullClaimsAndNullListInjectsNothing() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Accept", "application/json");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(entriesOf(incoming), Map.of(), null);

        assertNull(out.get("X-User-Id"));
        assertEquals("application/json", out.get("Accept"));
    }

    private List<Entry<String, String>> entriesOf(MultiMap mm) {
        return mm.entries();
    }
}
