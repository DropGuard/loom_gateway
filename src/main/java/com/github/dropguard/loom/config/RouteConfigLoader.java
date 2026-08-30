package com.github.dropguard.loom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.dropguard.loom.exception.ConfigException;
import com.github.dropguard.loom.model.GatewayConfig;
import com.github.dropguard.loom.model.RouteConfig;
import com.github.dropguard.loom.model.RouteConfig.AuthConfig;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link RouteConfigProvider} that loads routes from a YAML file on the filesystem. In production
 * this is the mounted ConfigMap (/config/routes.yaml); tests override the {@code
 * gateway.config.path} property to point at a test file. The source is resolved here and surfaced
 * only through the interface.
 *
 * <p>The YAML is read as text first and {@code ${ENV:default}} placeholders are expanded from the
 * process environment before Jackson parses it. This is what makes {@code secret:
 * "${JWT_SECRET:...}"} in the production routes actually work — Quarkus' SmallRye {@code ${...}}
 * expansion does not apply inside files loaded by a plain Jackson {@code ObjectMapper}, so without
 * this step the literal placeholder string was used as the HMAC secret.
 */
@ApplicationScoped
public class RouteConfigLoader implements RouteConfigProvider {

    private static final Logger LOG = Logger.getLogger(RouteConfigLoader.class);
    private static final long WATCH_POLL_INTERVAL_MILLIS = 2000;

    /**
     * Matches {@code ${NAME}} and {@code ${NAME:default}}. Group 1 = env var name, group 2 =
     * default (nullable).
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private volatile GatewayConfig config = GatewayConfig.EMPTY;
    private volatile Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    @Inject Event<RouteConfigReloadedEvent> reloadEvent;

    @ConfigProperty(name = "gateway.config.path", defaultValue = "/config/routes.yaml")
    String configPath;

    @ConfigProperty(name = "gateway.rate-limit.maximum-size", defaultValue = "10000")
    int rateLimitMaxBuckets;

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
            GatewayConfig newConfig = readConfig(configFile);
            applyNewConfig(newConfig);
            startWatching(configFile);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load configuration from: %s", path);
        }
    }

    /**
     * Watches the config file by polling its last-modified time. This is deliberate: Kubernetes
     * mounts ConfigMaps through symlinks ({@code ..data -> ..<timestamp>/}) and kubelet swaps the
     * symlink atomically on update, so a {@link java.nio.file.WatchService} registered on the
     * parent directory never observes an ENTRY_MODIFY on {@code routes.yaml} itself. Polling mtime
     * (which follows the symlink to the freshly rotated target) catches the switch reliably.
     */
    private void startWatching(Path configFile) {
        Thread.ofVirtual()
                .name("config-watcher")
                .start(
                        () -> {
                            long lastModified = lastModifiedOf(configFile);
                            LOG.infof("Started watching configuration file: %s", configFile);
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    Thread.sleep(WATCH_POLL_INTERVAL_MILLIS);
                                    long current = lastModifiedOf(configFile);
                                    if (current != lastModified) {
                                        lastModified = current;
                                        LOG.infof(
                                                "Configuration file changed, reloading: %s",
                                                configFile);
                                        reloadConfig(configFile);
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    LOG.info("Config watcher thread interrupted");
                                    return;
                                }
                            }
                        });
    }

    private static long lastModifiedOf(Path configFile) {
        try {
            return Files.getLastModifiedTime(configFile).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Reads the file, expands ${ENV:default} placeholders, then parses YAML. */
    private GatewayConfig readConfig(Path configFile) throws IOException {
        String raw = Files.readString(configFile);
        String resolved = resolvePlaceholders(raw);
        return yamlMapper.readValue(resolved, GatewayConfig.class);
    }

    /**
     * Replaces every {@code ${NAME:default}} occurrence with the environment variable {@code NAME}
     * when set, otherwise with {@code default} (empty string when the default is absent). Unknown
     * variables without a default resolve to an empty string so a missing secret fails loudly at
     * auth time (401/500) instead of silently using the literal placeholder as the key.
     */
    static String resolvePlaceholders(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String value = System.getenv(envName);
            if (value == null || value.isEmpty()) {
                value = matcher.group(2) != null ? matcher.group(2) : "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void validateAuthConfig(RouteConfig route) {
        AuthConfig auth = route.filters().auth();
        if (auth == null) {
            return;
        }
        String type = auth.type() != null ? auth.type().toLowerCase() : "";
        if ("jwt".equals(type)) {
            String secret = auth.secret();
            if (secret == null || secret.isEmpty()) {
                throw new ConfigException(
                        "JWT secret must be configured for route '" + route.id() + "'");
            }
        } else if ("api-key".equals(type)) {
            List<String> apiKeys = auth.apiKeys();
            if (apiKeys == null) {
                throw new ConfigException(
                        "API key list must be configured for route '" + route.id() + "'");
            }
            for (String key : apiKeys) {
                if (key == null || key.isEmpty()) {
                    throw new ConfigException(
                            "API key cannot be empty for route '" + route.id() + "'");
                }
            }
        }
    }

    private void reloadConfig(Path configFile) {
        try {
            GatewayConfig newConfig = readConfig(configFile);
            applyNewConfig(newConfig);
            reloadEvent.fire(new RouteConfigReloadedEvent(newConfig));
        } catch (IOException e) {
            LOG.errorf(e, "Failed to reload configuration, keeping current config");
        }
    }

    void applyNewConfig(GatewayConfig newConfig) {
        Map<String, Pattern> newPatterns = new ConcurrentHashMap<>();
        Set<String> seenIds = new HashSet<>();
        if (newConfig.routes() != null) {
            for (RouteConfig route : newConfig.routes()) {
                // Duplicate route ids silently overwrite the compiled pattern and
                // make the first route unreachable; reject the whole update and
                // keep the current config instead.
                if (!seenIds.add(route.id())) {
                    LOG.errorf(
                            "Duplicate route id '%s' in configuration; keeping current config",
                            route.id());
                    return;
                }
                // Validate auth config (fail‑fast)
                validateAuthConfig(route);
                newPatterns.put(route.id(), compilePathPattern(route.path()));
            }
        }
        this.compiledPatterns = newPatterns;
        this.config = newConfig;
        LOG.infof(
                "Applied configuration with %d routes",
                newConfig.routes() != null ? newConfig.routes().size() : 0);
    }

    /**
     * Compiles a route path pattern, quoting every literal segment so regex metacharacters in paths
     * (e.g. {@code /api/v1.0/**}, {@code /api/(v2)/**}) cannot change matching semantics. A
     * trailing {@code /**} keeps its original meaning: the base path itself also matches.
     */
    private Pattern compilePathPattern(String pathPattern) {
        if (pathPattern.endsWith("/**")) {
            String basePath = pathPattern.substring(0, pathPattern.length() - 3);
            return Pattern.compile("^" + Pattern.quote(basePath) + "(?:/.*)?$");
        }
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < pathPattern.length()) {
            char c = pathPattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pathPattern.length() && pathPattern.charAt(i + 1) == '*') {
                    sb.append("__DOUBLESTAR__");
                    i += 2;
                } else {
                    sb.append("__SINGLESTAR__");
                    i++;
                }
            } else {
                int next = pathPattern.indexOf('*', i);
                String literal =
                        next < 0 ? pathPattern.substring(i) : pathPattern.substring(i, next);
                sb.append(Pattern.quote(literal));
                i = next < 0 ? pathPattern.length() : next;
            }
        }
        String regex =
                sb.toString().replace("__DOUBLESTAR__", ".*").replace("__SINGLESTAR__", "[^/]+");
        return Pattern.compile(regex + "$");
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
