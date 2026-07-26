package com.github.dropguard.loom.config;

import com.github.dropguard.loom.model.GatewayConfig;

/**
 * CDI event fired when route configuration is reloaded.
 */
public record RouteConfigReloadedEvent(GatewayConfig newConfig) {
}
