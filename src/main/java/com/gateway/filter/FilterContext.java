package com.gateway.filter;

import com.gateway.model.RouteConfig;
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

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    /**
     * Gets the effective upstream, may be overridden by gray release.
     */
    public String effectiveUpstream() {
        String override = getAttribute("grayUpstream");
        return override != null ? override : (route() != null ? route().upstream() : null);
    }

    /**
     * Sets the target upstream, used by gray release filter.
     */
    public void targetUpstream(String upstream) {
        setAttribute("grayUpstream", upstream);
    }
}
