package com.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Gateway configuration model containing all routes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayConfig(
    List<RouteConfig> routes
) {
    public static final GatewayConfig EMPTY = new GatewayConfig(List.of());
}
