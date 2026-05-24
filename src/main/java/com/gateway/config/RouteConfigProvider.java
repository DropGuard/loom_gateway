package com.gateway.config;

import com.gateway.model.GatewayConfig;
import com.gateway.model.RouteConfig;

public interface RouteConfigProvider {
    RouteConfig findMatchingRoute(String path, String method);
    GatewayConfig getConfig();
}
