package com.github.dropguard.loom.config;

import com.github.dropguard.loom.model.GatewayConfig;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Initializes the gateway configuration on application startup.
 *
 * <p>Config loading is owned by the {@link RouteConfigLoader} (a {@link RouteConfigProvider}),
 * which auto-loads on construction. This initializer only observes startup to log/validate.
 * Injecting the interface (not the concrete loader) is what lets tests supply a different config
 * source via CDI alternatives (DEFECT T1 fix).
 */
@ApplicationScoped
public class GatewayConfigInitializer {

    private static final Logger LOG = Logger.getLogger(GatewayConfigInitializer.class);

    @Inject RouteConfigProvider routeConfigProvider;

    void onStart(@Observes StartupEvent ev) {
        GatewayConfig config = routeConfigProvider.getConfig();
        if (config == null || config.routes() == null || config.routes().isEmpty()) {
            // DEFECT #11: a missing/empty config must be loud.
            LOG.errorf(
                    "CRITICAL: gateway started with NO routes (configMissing). Readiness will "
                            + "stay DOWN and Kubernetes will never restart this pod. Check gateway.config.path.");
        } else {
            LOG.infof("Gateway configuration initialized with %d routes", config.routes().size());
        }
    }
}
