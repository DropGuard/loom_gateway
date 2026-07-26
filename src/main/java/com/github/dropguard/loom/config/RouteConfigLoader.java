package com.github.dropguard.loom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.dropguard.loom.model.GatewayConfig;
import com.github.dropguard.loom.model.RouteConfig;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link RouteConfigProvider} that loads routes from a YAML file on the filesystem. In production
 * this is the mounted ConfigMap (/config/routes.yaml); tests override the {@code
 * gateway.config.path} property to point at a test file. The source is resolved here and surfaced
 * only through the interface.
 */
@ApplicationScoped
public class RouteConfigLoader implements RouteConfigProvider {

    private static final Logger LOG = Logger.getLogger(RouteConfigLoader.class);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private volatile GatewayConfig config = GatewayConfig.EMPTY;
    private volatile Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    @Inject Event<RouteConfigReloadedEvent> reloadEvent;

    @ConfigProperty(name = "gateway.config.path", defaultValue = "/config/routes.yaml")
    String configPath;

    @PostConstruct
    void load() {
        String resolved = resolveConfigPath(configPath);
        loadFromPath(resolved);
    }

    /**
     * Configured path first, then a relative fallback for local dev (mvn quarkus:dev), then the
     * configured path as-is.
     */
    private String resolveConfigPath(String path) {
        if (Files.exists(Paths.get(path))) {
            return path;
        }
        Path relative = Paths.get("config", "routes.yaml");
        if (Files.exists(relative)) {
            LOG.infof("Config not found at '%s', using relative path '%s'", path, relative);
            return relative.toString();
        }
        LOG.warnf("Config not found at '%s' or '%s'", path, relative);
        return path;
    }

    void loadFromPath(String path) {
        LOG.infof("Loading route configuration from: %s", path);
        Path configFile = Paths.get(path);

        if (!Files.exists(configFile)) {
            // DEFECT #11: a missing critical config must be loud. The gateway
            // would otherwise start with EMPTY routes, report readiness DOWN
            // forever while liveness stays UP, and serve 404s with no restart.
            LOG.errorf(
                    "CRITICAL: Configuration file not found: %s. Gateway will start with "
                            + "NO routes (readiness DOWN). Fix the path or the file, or Kubernetes "
                            + "will never restart this pod.",
                    path);
            return;
        }

        try {
            GatewayConfig newConfig =
                    yamlMapper.readValue(configFile.toFile(), GatewayConfig.class);
            applyNewConfig(newConfig);
            startWatching(configFile);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load configuration from: %s", path);
        }
    }

    private void startWatching(Path configFile) {
        Thread.ofVirtual()
                .name("config-watcher")
                .start(
                        () -> {
                            try (WatchService watchService =
                                    FileSystems.getDefault().newWatchService()) {
                                Path parentDir = configFile.getParent();
                                if (parentDir != null) {
                                    parentDir.register(
                                            watchService,
                                            StandardWatchEventKinds.ENTRY_MODIFY,
                                            StandardWatchEventKinds.ENTRY_CREATE);
                                }

                                LOG.infof("Started watching configuration file: %s", configFile);

                                while (!Thread.currentThread().isInterrupted()) {
                                    WatchKey key = watchService.take();
                                    for (WatchEvent<?> event : key.pollEvents()) {
                                        Path changedFile = (Path) event.context();
                                        if (configFile
                                                .getFileName()
                                                .toString()
                                                .equals(changedFile.toString())) {
                                            LOG.infof(
                                                    "Configuration file changed, reloading: %s",
                                                    changedFile);
                                            reloadConfig(configFile);
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

    private void reloadConfig(Path configFile) {
        try {
            GatewayConfig newConfig =
                    yamlMapper.readValue(configFile.toFile(), GatewayConfig.class);
            applyNewConfig(newConfig);
            reloadEvent.fire(new RouteConfigReloadedEvent(newConfig));
        } catch (IOException e) {
            LOG.errorf(e, "Failed to reload configuration, keeping current config");
        }
    }

    void applyNewConfig(GatewayConfig newConfig) {
        Map<String, Pattern> newPatterns = new ConcurrentHashMap<>();
        if (newConfig.routes() != null) {
            for (RouteConfig route : newConfig.routes()) {
                newPatterns.put(route.id(), compilePathPattern(route.path()));
            }
        }
        this.compiledPatterns = newPatterns;
        this.config = newConfig;
        LOG.infof(
                "Applied configuration with %d routes",
                newConfig.routes() != null ? newConfig.routes().size() : 0);
    }

    private Pattern compilePathPattern(String pathPattern) {
        String regex;
        if (pathPattern.endsWith("/**")) {
            String basePath = pathPattern.substring(0, pathPattern.length() - 3);
            regex = basePath + "(?:/.*)?";
        } else {
            regex =
                    pathPattern
                            .replace("**", "__DOUBLESTAR__")
                            .replace("*", "[^/]+")
                            .replace("__DOUBLESTAR__", ".*");
        }
        return Pattern.compile("^" + regex + "$");
    }

    @Override
    public RouteConfig findMatchingRoute(String requestPath, String method) {
        GatewayConfig currentConfig = this.config;
        Map<String, Pattern> currentPatterns = this.compiledPatterns;

        if (currentConfig.routes() == null) {
            return null;
        }

        for (RouteConfig route : currentConfig.routes()) {
            Pattern pattern = currentPatterns.get(route.id());
            if (pattern != null && pattern.matcher(requestPath).matches()) {
                if (route.methods() == null
                        || route.methods().isEmpty()
                        || route.methods().contains(method.toUpperCase())) {
                    return route;
                }
            }
        }
        return null;
    }

    @Override
    public GatewayConfig getConfig() {
        return config;
    }
}
