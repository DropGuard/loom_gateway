package com.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gateway.model.GatewayConfig;
import com.gateway.model.RouteConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

/**
 * Route configuration loader with file watching support. Loads routes.yaml on
 * startup and watches for file changes to hot-reload.
 */
@ApplicationScoped
public class RouteConfigLoader implements RouteConfigProvider {

    private static final Logger LOG = Logger.getLogger(RouteConfigLoader.class);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private volatile GatewayConfig config = GatewayConfig.EMPTY;
    private volatile Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    @Inject
    Event<RouteConfigReloadedEvent> reloadEvent;

    /**
     * Load configuration from the specified YAML file path. Must be called at
     * application startup.
     */
    public void loadFromPath(String configPath) {
        LOG.infof("Loading route configuration from: %s", configPath);
        Path path = Paths.get(configPath);

        if (!Files.exists(path)) {
            LOG.warnf("Configuration file not found: %s, using empty config", configPath);
            return;
        }

        try {
            GatewayConfig newConfig = yamlMapper.readValue(path.toFile(), GatewayConfig.class);
            applyNewConfig(newConfig);
            startWatching(path);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load configuration from: %s", configPath);
        }
    }

    /**
     * Start watching the configuration file for changes.
     */
    private void startWatching(Path configPath) {
        Thread.ofVirtual().name("config-watcher").start(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Path parentDir = configPath.getParent();

                if (parentDir != null) {
                    parentDir.register(watchService,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE);
                }

                LOG.infof("Started watching configuration file: %s", configPath);

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.take();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changedFile = (Path) event.context();
                        if (configPath.getFileName().toString().equals(changedFile.toString())) {
                            LOG.infof("Configuration file changed, reloading: %s", changedFile);
                            reloadConfig(configPath);
                        }
                    }

                    key.reset();
                }
            } catch (InterruptedException e) {
                LOG.info("Config watcher thread interrupted");
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOG.errorf(e, "Failed to watch configuration file");
            }
        });
    }

    /**
     * Reload configuration from file.
     */
    private void reloadConfig(Path configPath) {
        try {
            GatewayConfig newConfig = yamlMapper.readValue(configPath.toFile(), GatewayConfig.class);
            applyNewConfig(newConfig);
            reloadEvent.fire(new RouteConfigReloadedEvent(newConfig));
        } catch (IOException e) {
            LOG.errorf(e, "Failed to reload configuration, keeping current config");
        }
    }

    /**
     * Apply new configuration and rebuild compiled patterns atomically.
     */
    void applyNewConfig(GatewayConfig newConfig) {
        Map<String, Pattern> newPatterns = new ConcurrentHashMap<>();
        if (newConfig.routes() != null) {
            for (RouteConfig route : newConfig.routes()) {
                newPatterns.put(route.id(), compilePathPattern(route.path()));
            }
        }
        // Atomic swap: patterns and config update together
        this.compiledPatterns = newPatterns;
        this.config = newConfig;
        LOG.infof("Applied configuration with %d routes",
                newConfig.routes() != null ? newConfig.routes().size() : 0);
    }

    /**
     * Convert route path pattern (e.g., /api/**) to regex pattern.
     */
    private Pattern compilePathPattern(String pathPattern) {
        String regex;
        if (pathPattern.endsWith("/**")) {
            String basePath = pathPattern.substring(0, pathPattern.length() - 3);
            regex = basePath + "(?:/.*)?";
        } else {
            regex = pathPattern
                    .replace("**", "__DOUBLESTAR__")
                    .replace("*", "[^/]+")
                    .replace("__DOUBLESTAR__", ".*");
        }
        return Pattern.compile("^" + regex + "$");
    }

    /**
     * Find matching route for the given request path and method.
     */
    public RouteConfig findMatchingRoute(String requestPath, String method) {
        GatewayConfig currentConfig = this.config;
        Map<String, Pattern> currentPatterns = this.compiledPatterns;

        if (currentConfig.routes() == null) {
            return null;
        }

        for (RouteConfig route : currentConfig.routes()) {
            Pattern pattern = currentPatterns.get(route.id());
            if (pattern != null && pattern.matcher(requestPath).matches()) {
                if (route.methods() == null || route.methods().isEmpty() ||
                        route.methods().contains(method.toUpperCase())) {
                    return route;
                }
            }
        }
        return null;
    }

    public GatewayConfig getConfig() {
        return config;
    }
}
