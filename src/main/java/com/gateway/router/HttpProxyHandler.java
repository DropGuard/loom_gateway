package com.gateway.router;

import com.gateway.exception.ConfigException;
import com.gateway.exception.UpstreamException;
import com.gateway.filter.CircuitBreakerFilter;
import com.gateway.filter.FilterContext;
import com.gateway.metrics.GatewayMetrics;

import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.HttpClientRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

/**
 * Handles HTTP proxy forwarding. Parses upstream URL, forwards headers,
 * sends request to upstream, and streams/buffers response back to client.
 */
@ApplicationScoped
public class HttpProxyHandler {

    private static final Logger LOG = Logger.getLogger(HttpProxyHandler.class);
    private final HttpClient httpClient;
    private final GatewayMetrics metrics;
    private final CircuitBreakerFilter circuitBreakerFilter;

    @Inject
    public HttpProxyHandler(Vertx vertx, GatewayMetrics metrics, CircuitBreakerFilter circuitBreakerFilter,
                            @ConfigProperty(name = "gateway.timeout", defaultValue = "5000") int timeoutMs) {
        this.httpClient = vertx.createHttpClient(
                new HttpClientOptions()
                        .setConnectTimeout(timeoutMs)
                        .setIdleTimeout(timeoutMs));
        this.metrics = metrics;
        this.circuitBreakerFilter = circuitBreakerFilter;
        LOG.infof("HTTP client initialized with timeout: %dms", timeoutMs);
    }

    /**
     * Proxy the HTTP request to the upstream service.
     */
    public void proxy(RoutingContext context, FilterContext filterContext) {
        io.vertx.core.http.HttpServerRequest incomingReq = context.request();
        HttpMethod method = HttpMethod.valueOf(incomingReq.method().name());

        String effectiveUpstream = filterContext.effectiveUpstream();
        if (effectiveUpstream == null || effectiveUpstream.isBlank()) {
            throw new ConfigException("No upstream resolved for route '"
                    + (filterContext.route() != null ? filterContext.route().id() : "?") + "'");
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
            Instant upstreamStart = Instant.now();
            HttpClientRequest backendReq = httpClient.request(method, port, host, uri)
                    .await().indefinitely();

            ProxyHeaders.buildForwardHeaders(incomingReq.headers().entries(), subClaim(filterContext.claims()))
                    .forEach(backendReq.headers()::add);

            io.vertx.core.buffer.Buffer body = context.body() != null ? context.body().buffer() : null;
            io.vertx.core.http.HttpServerResponse clientResp = context.response();

            backendReq.send(
                    io.vertx.mutiny.core.buffer.Buffer
                            .newInstance(body != null ? body : io.vertx.core.buffer.Buffer.buffer()))
                    .chain(backendRes -> {
                        metrics.recordUpstreamLatency(Duration.between(upstreamStart, Instant.now()));

                        ProxyHeaders.selectClientHeaders(backendRes.headers().entries())
                                .forEach((k, v) -> clientResp.headers().add(k, v));
                        clientResp.setStatusCode(backendRes.statusCode());
                        LOG.debugf("Upstream response: %d", backendRes.statusCode());

                        Consumer<FilterContext.ResponseData> interceptor =
                                filterContext.responseInterceptor();
                        if (interceptor != null) {
                            // Buffer mode: receive full body, pass to interceptor, then send
                            return backendRes.body()
                                    .onItem().invoke(mutinyBody -> {
                                        String contentType = backendRes.headers().get("content-type");
                                        interceptor.accept(new FilterContext.ResponseData(
                                                backendRes.statusCode(), contentType,
                                                mutinyBody.getBytes()));
                                    })
                                    .onItem().transformToUni(mutinyBody -> {
                                        return io.vertx.mutiny.core.http.HttpServerResponse
                                                .newInstance(clientResp).end(mutinyBody);
                                    });
                        } else {
                            // Stream mode: pipe directly to client
                            return backendRes.pipeTo(
                                    io.vertx.mutiny.core.http.HttpServerResponse.newInstance(clientResp));
                        }
                    }).await().indefinitely();

            // Request completed synchronously on this (virtual) thread. Report the
            // outcome to the circuit breaker — this is the single source of truth
            // for HTTP, symmetric with WebSocketProxyHandler's async recordOutcome.
            reportOutcome(filterContext, true);

        } catch (Exception e) {
            // Report the failure before rethrowing; the breaker is updated by the
            // handler, not by the CircuitBreakerFilter's post-check.
            reportOutcome(filterContext, false);
            Throwable cause = unwrapCompletionException(e);
            if (cause instanceof io.netty.handler.timeout.TimeoutException) {
                throw new UpstreamException(504, "Gateway timeout for " + effectiveUpstream, cause);
            }
            throw new UpstreamException("Proxy failed for " + effectiveUpstream, cause);
        }
    }

    private void reportOutcome(FilterContext filterContext, boolean success) {
        if (filterContext.route() == null) {
            return;
        }
        circuitBreakerFilter.recordOutcome(filterContext.route().id(), success);
    }

    private static String subClaim(java.util.Map<String, Object> claims) {
        if (claims == null) {
            return null;
        }
        Object sub = claims.get("sub");
        return sub == null ? null : sub.toString();
    }

    private static Throwable unwrapCompletionException(Throwable e) {
        while (e instanceof java.util.concurrent.CompletionException && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }
}
