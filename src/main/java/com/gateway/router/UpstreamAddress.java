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
        boolean ssl = false;
        String url = upstreamUrl;

        if (url.startsWith("https://")) {
            url = url.substring(8);
            ssl = true;
        } else if (url.startsWith("http://")) {
            url = url.substring(7);
        }

        String[] parts = url.split(":", 2);
        String host = parts[0];
        int port;
        if (parts.length > 1) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new ConfigException("Invalid port in upstream URL: " + upstreamUrl);
            }
        } else {
            port = ssl ? 443 : 80;
        }

        return new UpstreamAddress(host, port, ssl);
    }
}
