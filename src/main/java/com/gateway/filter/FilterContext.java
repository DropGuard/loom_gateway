package com.gateway.filter;

import com.gateway.model.RouteConfig;
import com.gateway.model.RouteConfig.FilterConfig;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context passed to each filter in the chain.
 */
public class FilterContext {

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private RouteConfig route;
    private String grayUpstream;
    private Map<String, Object> claims;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public FilterContext(HttpServerRequest request, HttpServerResponse response) {
        this.request = request;
        this.response = response;
    }

    public HttpServerRequest request() {
        return request;
    }

    public HttpServerResponse response() {
        return response;
    }

    public RouteConfig route() {
        return route;
    }

    public void route(RouteConfig route) {
        this.route = route;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public FilterConfig filters() {
        return route != null ? route.filters() : FilterConfig.EMPTY;
    }

    public Map<String, Object> claims() {
        return claims;
    }

    public void claims(Map<String, Object> claims) {
        this.claims = claims;
    }

    /**
     * Gets the effective upstream, may be overridden by gray release.
     */
    public String effectiveUpstream() {
        return grayUpstream != null ? grayUpstream : (route() != null ? route().upstream() : null);
    }

    public void targetUpstream(String upstream) {
        this.grayUpstream = upstream;
    }
}
