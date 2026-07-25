package com.gateway.router;

import static org.junit.jupiter.api.Assertions.*;

import com.gateway.exception.ConfigException;

import org.junit.jupiter.api.Test;

class UpstreamAddressTest {

    @Test
    void parsesHostOnlyDefaultsToHttp80() {
        UpstreamAddress a = UpstreamAddress.parse("example.com");
        assertEquals("example.com", a.host());
        assertEquals(80, a.port());
        assertFalse(a.ssl());
    }

    @Test
    void parsesHostPort() {
        UpstreamAddress a = UpstreamAddress.parse("example.com:8080");
        assertEquals("example.com", a.host());
        assertEquals(8080, a.port());
        assertFalse(a.ssl());
    }

    @Test
    void parsesHttpsDefaultsTo443() {
        UpstreamAddress a = UpstreamAddress.parse("https://example.com");
        assertEquals("example.com", a.host());
        assertEquals(443, a.port());
        assertTrue(a.ssl());
    }

    @Test
    void parsesHttpsWithPort() {
        UpstreamAddress a = UpstreamAddress.parse("https://example.com:8443");
        assertEquals("example.com", a.host());
        assertEquals(8443, a.port());
        assertTrue(a.ssl());
    }

    @Test
    void parsesServiceNameHost() {
        UpstreamAddress a = UpstreamAddress.parse("mock-backend:80");
        assertEquals("mock-backend", a.host());
        assertEquals(80, a.port());
    }

    // --- DEFECT #15: IPv6 literals ---
    @Test
    void parsesIpv6WithPort() {
        UpstreamAddress a = UpstreamAddress.parse("[::1]:8080");
        assertEquals("::1", a.host());
        assertEquals(8080, a.port());
        assertFalse(a.ssl());
    }

    @Test
    void parsesIpv6WithHttpsAndPort() {
        UpstreamAddress a = UpstreamAddress.parse("https://[2001:db8::1]:8443");
        assertEquals("2001:db8::1", a.host());
        assertEquals(8443, a.port());
        assertTrue(a.ssl());
    }

    @Test
    void parsesBareIpv6DefaultsTo80() {
        UpstreamAddress a = UpstreamAddress.parse("[::1]");
        assertEquals("::1", a.host());
        assertEquals(80, a.port());
    }

    // --- DEFECT #2: null / empty guards ---
    @Test
    void rejectsNull() {
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse(null));
    }

    @Test
    void rejectsBlank() {
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse("   "));
    }

    @Test
    void rejectsEmptyHost() {
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse(":8080"));
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse("example.com:notaport"));
    }

    @Test
    void rejectsMalformedIpv6() {
        assertThrows(ConfigException.class, () -> UpstreamAddress.parse("[::1"));
    }
}
