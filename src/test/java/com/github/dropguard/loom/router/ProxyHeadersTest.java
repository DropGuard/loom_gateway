package com.github.dropguard.loom.router;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.util.ApiKeyHasher;

import io.vertx.core.MultiMap;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

/**
 * Zero-mock unit tests for ProxyHeaders: the header transformations that gate what reaches the
 * upstream, asserted directly as pure mappings.
 */
class ProxyHeadersTest {

    @Test
    void unauthenticatedRoute_passesCredentialsButDropsForgedIdentityHeaders() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Authorization", "Bearer tok");
        incoming.add("X-API-Key", "client-key");
        incoming.add("Connection", "keep-alive");
        incoming.add("Upgrade", "websocket");
        incoming.add("Content-Length", "123");
        incoming.add("X-User-Id", "spoofed");
        incoming.add("X-Claim-role", "admin");
        incoming.add("X-Request-Id", "abc");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), null, null, null, "client-key");

        assertEquals("Bearer tok", out.get("Authorization"));
        assertEquals("client-key", out.get("X-API-Key"));
        assertEquals("abc", out.get("X-Request-Id"));
        assertNull(out.get("Connection"));
        assertNull(out.get("Upgrade"));
        assertNull(out.get("Content-Length"));
        assertNull(out.get("X-User-Id"));
        assertNull(out.get("X-Claim-role"));
    }

    @Test
    void jwtRoute_stripsCredentialsAndForgedIdentityHeaders() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Authorization", "Bearer tok");
        incoming.add("X-API-Key", "should-not-leak");
        incoming.add("X-User-Id", "spoofed");
        incoming.add("X-Claim-role", "admin");
        incoming.add("Accept", "application/json");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), Map.of("sub", "user-42"), List.of("sub"), "jwt", null);

        assertNull(out.get("Authorization"));
        assertNull(out.get("X-API-Key"));
        assertEquals("user-42", out.get("X-User-Id"));
        assertNull(out.get("X-Claim-role"));
        assertEquals("application/json", out.get("Accept"));
    }

    @Test
    void apiKeyRoute_stripsCredentialsAndInjectsHashedIdentity() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("X-API-Key", "secret-key");
        incoming.add("Authorization", "Bearer should-not-leak");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), null, null, "api-key", "secret-key");

        assertNull(out.get("X-API-Key"));
        assertNull(out.get("Authorization"));
        assertEquals(ApiKeyHasher.hash("secret-key"), out.get("X-User-Id"));
    }

    @Test
    void buildForwardHeaders_injectsUserFromSubClaim() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Accept", "application/json");

        Map<String, Object> claims = Map.of("sub", "user-42", "scope", "read");
        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), claims, List.of("sub"), "jwt", null);

        assertEquals("user-42", out.get("X-User-Id"));
        assertEquals("application/json", out.get("Accept"));
        assertNull(out.get("X-Claim-scope"), "claims outside the forward list must not leak");
    }

    @Test
    void buildForwardHeaders_noSubClaimDoesNotInjectUser() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Accept", "application/json");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming), Map.of(), List.of("sub"), "jwt", null);

        assertNull(out.get("X-User-Id"));
    }

    @Test
    void buildForwardHeaders_forwardsAdditionalClaimsAsXClaimPrefixed() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        Map<String, Object> claims = Map.of("sub", "user-7", "scope", "read", "tenant", "acme");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(
                        entriesOf(incoming),
                        claims,
                        List.of("sub", "scope", "tenant"),
                        "jwt",
                        null);

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
                        entriesOf(incoming), claims, List.of("sub", "scope"), "jwt", null);

        assertEquals("user-7", out.get("X-User-Id"));
        assertNull(out.get("X-Claim-scope"));
    }

    @Test
    void buildForwardHeaders_nullForwardListInjectsNoClaims() {
        MultiMap incoming = MultiMap.caseInsensitiveMultiMap();
        incoming.add("Accept", "application/json");

        Map<String, String> out =
                ProxyHeaders.buildForwardHeaders(entriesOf(incoming), Map.of(), null, "jwt", null);

        assertNull(out.get("X-User-Id"));
        assertEquals("application/json", out.get("Accept"));
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

    private List<Entry<String, String>> entriesOf(MultiMap mm) {
        return mm.entries();
    }
}
