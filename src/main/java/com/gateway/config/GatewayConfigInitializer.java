package com.gateway.config;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Initializes the gateway configuration on application startup.
 */
@ApplicationScoped
public class GatewayConfigInitializer {

    private static final Logger LOG = Logger.getLogger(GatewayConfigInitializer.class);

    @Inject
    RouteConfigLoader routeConfigLoader;

    @ConfigProperty(name = "gateway.config.path", defaultValue = "/config/routes.yaml")
    String configPath;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing gateway configuration");
        String resolvedPath = resolveConfigPath(configPath);
        routeConfigLoader.loadFromPath(resolvedPath);
    }

    private String resolveConfigPath(String path) {
        // If the configured path exists, use it
        if (Files.exists(Paths.get(path))) {
            return path;
        }
        // Fallback: try relative path (for local dev with `mvn quarkus:dev`)
        Path relative = Paths.get("config", "routes.yaml");
        if (Files.exists(relative)) {
            LOG.infof("Config not found at '%s', using relative path '%s'", path, relative);
            return relative.toString();
        }
        LOG.warnf("Config not found at '%s' or '%s'", path, relative);
        return path;
    }
}
