package com.github.dropguard.loom.filter;

import com.github.dropguard.loom.model.RouteConfig;
import com.github.dropguard.loom.model.RouteConfig.FilterConfig;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Context passed to each filter in the chain. */
public class FilterContext {

    /** Maximum body size (bytes) that a response interceptor may buffer. */
    public static final long MAX_INTERCEPTED_BODY_BYTES = 1024 * 1024;

    /** Captured upstream response data, passed to response interceptors. */
    public record ResponseData(
            int statusCode, String contentType, byte[] body, Map<String, String> headers) {}

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private RouteConfig route;
    private String grayUpstream;
    private Map<String, Object> claims;
    private Consumer<ResponseData> responseInterceptor;
    private byte[] requestBody;
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

    /** Gets the effective upstream, may be overridden by gray release. */
    public String effectiveUpstream() {
        return grayUpstream != null ? grayUpstream : (route() != null ? route().upstream() : null);
    }

    public void targetUpstream(String upstream) {
        this.grayUpstream = upstream;
    }

    /**
     * Request body captured by the router on the virtual thread before the filter chain runs.
     * Guarantees POST/PUT payloads are forwarded to upstream regardless of how the framework
     * buffers (or fails to buffer) the body alongside reactive filters.
     */
    public byte[] requestBody() {
        return requestBody;
    }

    public void requestBody(byte[] requestBody) {
        this.requestBody = requestBody;
    }

    /**
     * Registers a callback to intercept the upstream response before it is sent to the client. When
     * set, the proxy will buffer the response body (only when Content-Length is known and ≤ 1MB)
     * and pass it to the interceptor, then send to the client. When not set, the proxy streams
     * directly via pipeTo without buffering.
     */
    public void onResponse(Consumer<ResponseData> interceptor) {
        this.responseInterceptor = interceptor;
    }

    public Consumer<ResponseData> responseInterceptor() {
        return responseInterceptor;
    }
}
