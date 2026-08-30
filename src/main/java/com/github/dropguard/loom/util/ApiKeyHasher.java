package com.github.dropguard.loom.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for hashing API keys in a consistent way across the gateway.
 *
 * <p>Both the cache filter and the gray‑release filter should use the same hash so that a given API
 * key maps to the same identity for cache partitioning and canary routing decisions.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
        /* utility class – prevent instantiation */
    }

    /**
     * Returns a SHA‑256 hexadecimal representation of the supplied API key. If the SHA‑256
     * algorithm is unavailable (which should never happen on a standard JDK) the method falls back
     * to returning the raw key.
     *
     * @param apiKey the API key to hash; must not be {@code null}
     * @return a lower‑case hex string
     */
    public static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Extremely unlikely, but we keep the contract simple.
            return apiKey;
        }
    }
}
