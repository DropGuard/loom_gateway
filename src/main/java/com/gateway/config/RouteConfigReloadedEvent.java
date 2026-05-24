package com.gateway.config;

import com.gateway.model.GatewayConfig;

/**
 * CDI event fired when route configuration is reloaded.
 */
public record RouteConfigReloadedEvent(GatewayConfig newConfig) {
}
