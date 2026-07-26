package com.github.dropguard.loom.router;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dropguard.loom.exception.ConfigException;

import org.junit.jupiter.api.Test;

/**
 * Zero-mock unit tests for UpstreamAddress.parse: the URL parsing that HttpProxyHandler relies on
 * but previously only exercised via integration tests. Locks
 * default/scheme/explicit-port/IPv6/error branches.
 */
class UpstreamAddressTest {

    @Test
    void hostOnly_usesDefaultPortForScheme() {
        assertEquals(new UpstreamAddress("backend", 80, false), UpstreamAddress.parse("backend"));
        assertEquals(
                new UpstreamAddress("backend", 443, true),
                UpstreamAddress.parse("https://backend"));
    }

    @Test
    void explicitPort_overridesDefault() {
        assertEquals(
                new UpstreamAddress("backend", 8080, false), UpstreamAddress.parse("backend:8080"));
        assertEquals(
                new UpstreamAddress("backend", 8443, true),
                UpstreamAddress.parse("https://backend:8443"));
    }

    @Test
    void httpScheme_isNotSsl() {
        UpstreamAddress a = UpstreamAddress.parse("http://backend:9090");
        assertFalse(a.ssl());
        assertEquals(9090, a.port());
    }

    @Test
    void ipv6Literal_withAndWithoutPort() {
        assertEquals(new UpstreamAddress("::1", 80, false), UpstreamAddress.parse("[::1]"));
        assertEquals(
                new UpstreamAddress("2001:db8::1", 9090, false),
                UpstreamAddress.parse("[2001:db8::1]:9090"));
    }

    @Test
    void malformed_throwsConfigException() {
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse(null));
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse(""));
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse("  "));
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse("[::1")); // missing ']'
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse("backend:notaport"));
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse("backend:8080:extra"));
    }
}
