package com.github.dropguard.loom.router;

import com.github.dropguard.loom.exception.ConfigException;
import com.github.dropguard.loom.exception.UpstreamException;
import com.github.dropguard.loom.filter.CircuitBreakerFilter;
import com.github.dropguard.loom.filter.FilterContext;
import com.github.dropguard.loom.metrics.GatewayMetrics;
import com.github.dropguard.loom.model.RouteConfig.TimeoutConfig;

import io.opentelemetry.api.trace.Span;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.HttpClientRequest;
import io.vertx.mutiny.core.http.HttpClientResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Handles HTTP proxy forwarding. Parses upstream URL, forwards headers, sends request to upstream,
 * and streams/buffers response back to client.
 */
@ApplicationScoped
public class HttpProxyHandler {

    private static final Logger LOG = Logger.getLogger(HttpProxyHandler.class);
    private final HttpClient httpClient;
    private final GatewayMetrics metrics;
    private final CircuitBreakerFilter circuitBreakerFilter;
    private final int defaultTimeoutMs;

    @Inject
    public HttpProxyHandler(
            Vertx vertx,
            GatewayMetrics metrics,
            CircuitBreakerFilter circuitBreakerFilter,
            @ConfigProperty(name = "gateway.timeout", defaultValue = "5000") int timeoutMs) {
        this.httpClient =
                vertx.createHttpClient(
                        new HttpClientOptions()
                                .setMaxPoolSize(500)
                                .setKeepAlive(true)
                                .setConnectTimeout(timeoutMs)
                                // DEFECT: Vert.x idleTimeout defaults to SECONDS while
                                // gateway.timeout is milliseconds. Expressing the unit explicitly
                                // prevents a 500ms timeout from silently becoming a 500-second
                                // idle timeout (~8 minutes of pinned idle connections).
                                .setIdleTimeout(timeoutMs)
                                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS));
        this.metrics = metrics;
        this.circuitBreakerFilter = circuitBreakerFilter;
        this.defaultTimeoutMs = timeoutMs;
        LOG.infof("HTTP client initialized with timeout: %dms (maxPoolSize: 500)", timeoutMs);
    }

    /** Proxy the HTTP request to the upstream service. */
    public void proxy(RoutingContext context, FilterContext filterContext) {
        io.vertx.core.http.HttpServerRequest incomingReq = context.request();
        HttpMethod method = HttpMethod.valueOf(incomingReq.method().name());
        long startNanos = System.nanoTime();

        String effectiveUpstream = filterContext.effectiveUpstream();
        if (effectiveUpstream == null || effectiveUpstream.isBlank()) {
            throw new ConfigException(
                    "No upstream resolved for route '"
                            + (filterContext.route() != null ? filterContext.route().id() : "?")
                            + "'");
        }
        UpstreamAddress upstream = UpstreamAddress.parse(effectiveUpstream);
        String host = upstream.host();
        int port = upstream.port();
        boolean ssl = upstream.ssl();

        String uri = incomingReq.path();
        String queryString = incomingReq.query();
        if (queryString != null) {
            uri += "?" + queryString;
        }

        LOG.debugf("Forwarding to: %s:%d%s", host, port, uri);

        try {
            // Per-route timeout (mirrors SCG's Timeout filter). Falls back to the
            // global gateway.timeout when the route has no explicit timeout.
            TimeoutConfig routeTimeout = filterContext.filters().timeout();
            Duration timeout =
                    routeTimeout != null && routeTimeout.timeoutMs() > 0
                            ? Duration.ofMillis(routeTimeout.timeoutMs())
                            : Duration.ofMillis(defaultTimeoutMs);

            // DEFECT: an https:// upstream previously reached the server as plain
            // HTTP (the shared client had no ssl flag, and ssl was never applied
            // per-request). RequestOptions.setSsl carries the scheme end-to-end.
            RequestOptions options =
                    new RequestOptions()
                            .setMethod(method)
                            .setHost(host)
                            .setPort(port)
                            .setURI(uri)
                            .setSsl(ssl);
            HttpClientRequest backendReq = httpClient.request(options).await().atMost(timeout);

            ProxyHeaders.buildForwardHeaders(
                            incomingReq.headers().entries(),
                            filterContext.claims(),
                            filterContext.filters().forwardClaims())
                    .forEach(backendReq.headers()::add);

            // Request body: prefer the body captured on the virtual thread by the
            // router (so POST/PUT payloads survive the whole filter chain); fall
            // back to Vert.x's buffered body when it is present. Note: on a GET
            // the framework still creates a RequestBody whose buffer() is null,
            // so both paths must null-check their source.
            byte[] bodyBytes = filterContext.requestBody();
            if (bodyBytes == null && context.body() != null && context.body().buffer() != null) {
                bodyBytes = context.body().buffer().getBytes();
            }
            Buffer body = bodyBytes != null ? Buffer.buffer(bodyBytes) : null;
            HttpServerResponse clientResp = context.response();

            backendReq
                    .send(
                            io.vertx.mutiny.core.buffer.Buffer.newInstance(
                                    body != null ? body : Buffer.buffer()))
                    .chain(
                            backendRes ->
                                    forwardResponse(
                                            clientResp, backendRes, startNanos, filterContext))
                    .await()
                    .atMost(timeout);

            // Request completed synchronously on this (virtual) thread. Report the
            // outcome to the circuit breaker — this is the single source of truth
            // for HTTP, symmetric with WebSocketProxyHandler's async recordOutcome.
            reportOutcome(filterContext, true);

        } catch (Exception e) {
            // Report the failure before rethrowing; the breaker is updated by the
            // handler, not by the CircuitBreakerFilter's post-check.
            reportOutcome(filterContext, false);
            Throwable cause = unwrapCompletionException(e);
            if (cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof io.smallrye.mutiny.TimeoutException) {
                throw new UpstreamException(504, "Gateway timeout for " + effectiveUpstream, cause);
            }
            throw new UpstreamException("Proxy failed for " + effectiveUpstream, cause);
        }
    }

    /**
     * Copies the upstream response to the client. When a response interceptor is registered AND the
     * upstream advertises a Content-Length within the interceptor limit AND the response is not
     * marked {@code no-store}, the body is buffered so the interceptor (e.g. the cache) can inspect
     * it. Otherwise the response is streamed directly and the interceptor is skipped — a >1MB or
     * chunked response must never be buffered into heap just to be told "too large to cache"
     * afterwards.
     */
    private Uni<Void> forwardResponse(
            HttpServerResponse clientResp,
            HttpClientResponse backendRes,
            long startNanos,
            FilterContext filterContext) {
        metrics.recordUpstreamLatency(System.nanoTime() - startNanos);

        ProxyHeaders.selectClientHeaders(backendRes.headers().entries())
                .forEach((k, v) -> clientResp.headers().add(k, v));
        // Benchmark response headers (mirror SCG ResponseHeaders filter):
        // tag the implementation and correlate the request. X-Request-Id reuses
        // the OpenTelemetry traceId so the response header and the gateway logs
        // are greppable on the same id.
        clientResp.headers().add("X-Gateway", "loom");
        String traceId = Span.current().getSpanContext().getTraceId();
        clientResp
                .headers()
                .add(
                        "X-Request-Id",
                        traceId != null && !traceId.isEmpty()
                                ? traceId
                                : Long.toHexString(ThreadLocalRandom.current().nextLong()));
        long processMs = (System.nanoTime() - startNanos) / 1_000_000L;
        clientResp.headers().add("X-Process-Time", processMs + "ms");
        clientResp.setStatusCode(backendRes.statusCode());
        LOG.debugf("Upstream response: %d (%dms)", backendRes.statusCode(), processMs);

        Consumer<FilterContext.ResponseData> interceptor = filterContext.responseInterceptor();
        if (interceptor != null && isBufferingSafe(backendRes) && !isNoStore(backendRes)) {
            // Buffer mode: receive full body, pass to interceptor, then send.
            return backendRes
                    .body()
                    .onItem()
                    .invoke(
                            mutinyBody -> {
                                String contentType = backendRes.headers().get("content-type");
                                Map<String, String> responseHeaders = new HashMap<>();
                                backendRes
                                        .headers()
                                        .entries()
                                        .forEach(
                                                e -> responseHeaders.put(e.getKey(), e.getValue()));
                                interceptor.accept(
                                        new FilterContext.ResponseData(
                                                backendRes.statusCode(),
                                                contentType,
                                                mutinyBody.getBytes(),
                                                responseHeaders));
                            })
                    .onItem()
                    .transformToUni(
                            mutinyBody ->
                                    io.vertx.mutiny.core.http.HttpServerResponse.newInstance(
                                                    clientResp)
                                            .end(mutinyBody));
        }
        // Stream mode: pipe directly to client (bypasses the interceptor).
        return backendRes.pipeTo(
                io.vertx.mutiny.core.http.HttpServerResponse.newInstance(clientResp));
    }

    /** Only responses with a known, interceptor-sized Content-Length are safe to buffer. */
    private static boolean isBufferingSafe(HttpClientResponse response) {
        String length = response.headers().get("content-length");
        if (length == null) {
            return false;
        }
        try {
            return Long.parseLong(length) <= FilterContext.MAX_INTERCEPTED_BODY_BYTES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A {@code Cache-Control: no-store} response must never be buffered/inspected for caching. */
    private static boolean isNoStore(HttpClientResponse response) {
        String cc = response.headers().get("cache-control");
        if (cc == null) {
            return false;
        }
        return cc.toLowerCase().contains("no-store");
    }

    private void reportOutcome(FilterContext filterContext, boolean success) {
        if (filterContext.route() == null) {
            return;
        }
        circuitBreakerFilter.recordOutcome(filterContext.route().id(), success);
    }

    private static Throwable unwrapCompletionException(Throwable e) {
        while ((e instanceof CompletionException
                        || e instanceof io.smallrye.mutiny.TimeoutException)
                && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }
}
