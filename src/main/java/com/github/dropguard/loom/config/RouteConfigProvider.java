package com.github.dropguard.loom.config;

import com.github.dropguard.loom.model.GatewayConfig;
import com.github.dropguard.loom.model.RouteConfig;

public interface RouteConfigProvider {
    RouteConfig findMatchingRoute(String path, String method);
    GatewayConfig getConfig();
}
