package com.gateway.router;

import com.gateway.exception.ConfigException;

/**
 * Parsed upstream address with host, port, and SSL flag.
 */
public record UpstreamAddress(String host, int port, boolean ssl) {

    /**
     * Parse an upstream URL string into an UpstreamAddress.
     * Supports formats: "host", "host:port", "http://host", "https://host:port"
     */
    public static UpstreamAddress parse(String upstreamUrl) {
        if (upstreamUrl == null || upstreamUrl.isBlank()) {
            throw new ConfigException("Upstream URL is null or empty");
        }

        boolean ssl = false;
        String url = upstreamUrl.trim();

        if (url.startsWith("https://")) {
            url = url.substring(8);
            ssl = true;
        } else if (url.startsWith("http://")) {
            url = url.substring(7);
        }

        String host;
        int port;
        if (url.startsWith("[")) {
            // IPv6 literal, e.g. [::1]:8080 or [2001:db8::1]
            int close = url.indexOf(']');
            if (close < 0) {
                throw new ConfigException("Malformed IPv6 upstream URL (missing ']'): " + upstreamUrl);
            }
            host = url.substring(1, close);
            String rest = url.substring(close + 1);
            port = parsePort(rest, ssl, upstreamUrl);
        } else {
            int colon = url.indexOf(':');
            if (colon < 0) {
                host = url;
                port = ssl ? 443 : 80;
            } else {
                host = url.substring(0, colon);
                port = parsePort(url.substring(colon), ssl, upstreamUrl);
            }
        }

        if (host.isEmpty()) {
            throw new ConfigException("Empty host in upstream URL: " + upstreamUrl);
        }

        return new UpstreamAddress(host, port, ssl);
    }

    private static int parsePort(String afterHost, boolean ssl, String original) {
        // afterHost looks like ":8080" or "" (no port present).
        if (afterHost.isEmpty() || ":".equals(afterHost)) {
            return ssl ? 443 : 80;
        }
        if (!afterHost.startsWith(":")) {
            throw new ConfigException("Malformed upstream URL (unexpected ':' placement): " + original);
        }
        try {
            return Integer.parseInt(afterHost.substring(1));
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid port in upstream URL: " + original);
        }
    }
}
