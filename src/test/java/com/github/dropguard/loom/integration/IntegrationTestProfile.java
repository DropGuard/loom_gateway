package com.github.dropguard.loom.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Shared profile for integration tests. Config is sourced from a test routes
 * file (src/test/resources/config/routes.yaml) by overriding the
 * {@code gateway.config.path} property -- the same file-based loader as
 * production, just pointed at a version-controlled test fixture whose upstreams
 * target the in-test MockBackend. No bean replacement; only a config override.
 */
public class IntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("gateway.config.path", "src/test/resources/config/routes.yaml");
    }
}
