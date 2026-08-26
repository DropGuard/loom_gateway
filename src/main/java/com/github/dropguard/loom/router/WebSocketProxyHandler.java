package com.github.dropguard.loom.router;

import com.github.dropguard.loom.exception.ConfigException;
import com.github.dropguard.loom.filter.CircuitBreakerFilter;
import com.github.dropguard.loom.filter.FilterContext;
import com.github.dropguard.loom.metrics.GatewayMetrics;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.RoutingContext;

import jakarta.inject.Singleton;

import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Singleton
public class WebSocketProxyHandler {

    private static final Logger LOG = Logger.getLogger(WebSocketProxyHandler.class);
    private static final short WS_CLOSE_UNEXPECTED_CONDITION = 1011;
    private final HttpClient httpClient;
    private final GatewayMetrics metrics;
    private final CircuitBreakerFilter circuitBreakerFilter;
    private final int connectTimeoutMs;

    public WebSocketProxyHandler(
            Vertx vertx,
            GatewayMetrics metrics,
            CircuitBreakerFilter circuitBreakerFilter,
            @ConfigProperty(name = "gateway.timeout", defaultValue = "5000") int connectTimeoutMs) {
        this.httpClient = vertx.createHttpClient();
        this.metrics = metrics;
        this.circuitBreakerFilter = circuitBreakerFilter;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public void proxy(RoutingContext context, FilterContext filterContext) {
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

        String uri = context.request().path();
        String queryString = context.request().query();
        if (queryString != null) {
            uri += "?" + queryString;
        }

        String routeId = filterContext.route() != null ? filterContext.route().id() : "?";

        WebSocketConnectOptions opts =
                new WebSocketConnectOptions()
                        .setHost(host)
                        .setPort(port)
                        .setURI(uri)
                        .setSsl(ssl)
                        // DEFECT: the upstream WebSocket connect previously had NO
                        // timeout; a black-holed/unreachable upstream could pin the
                        // client handshake (and the gateway's connection slot) for
                        // as long as the OS TCP timeout. Reuse gateway.timeout.
                        .setConnectTimeout(connectTimeoutMs);

        String subprotocol = context.request().getHeader("Sec-WebSocket-Protocol");
        if (subprotocol != null) {
            for (String proto : subprotocol.split(",")) {
                opts.addSubProtocol(proto.trim());
            }
        }

        Map<String, Object> claims = filterContext.claims();
        if (claims != null && claims.get("sub") != null) {
            opts.addHeader("X-User-Id", claims.get("sub").toString());
        }

        String finalUri = uri;
        context.request()
                .toWebSocket()
                .onSuccess(
                        clientWs -> {
                            clientWs.pause();
                            httpClient
                                    .webSocket(opts)
                                    .onSuccess(
                                            upstreamWs -> {
                                                circuitBreakerFilter.recordOutcome(routeId, true);
                                                metrics.incrementWsConnections();
                                                LOG.debugf(
                                                        "WebSocket connected: %s -> %s:%d%s",
                                                        routeId, host, port, finalUri);
                                                pipeFrames(clientWs, upstreamWs);
                                                clientWs.resume();
                                            })
                                    .onFailure(
                                            err -> {
                                                circuitBreakerFilter.recordOutcome(routeId, false);
                                                LOG.errorf(
                                                        err,
                                                        "Failed to connect upstream WebSocket: %s:%d%s",
                                                        host,
                                                        port,
                                                        finalUri);
                                                // The upstream connect failed after the client
                                                // handshake already succeeded. Close the client
                                                // socket only if it is still open — Vert.x throws
                                                // IllegalStateException("WebSocket is closed") if
                                                // close() is called twice (e.g. the client already
                                                // bailed when the gateway sent the 1011 close
                                                // frame), which surfaces as an uncaught exception
                                                // on the event loop.
                                                if (!clientWs.isClosed()) {
                                                    clientWs.close(
                                                            WS_CLOSE_UNEXPECTED_CONDITION,
                                                            "Upstream connection failed");
                                                }
                                            });
                        })
                .onFailure(
                        err -> {
                            LOG.errorf(err, "Failed to upgrade client WebSocket");
                            if (!context.response().ended()) {
                                context.response()
                                        .setStatusCode(400)
                                        .end("WebSocket upgrade failed");
                            }
                        });
    }

    private void pipeFrames(ServerWebSocket clientWs, WebSocket upstreamWs) {
        clientWs.textMessageHandler(msg -> upstreamWs.writeTextMessage(msg));
        clientWs.binaryMessageHandler(buf -> upstreamWs.writeBinaryMessage(buf));

        upstreamWs.textMessageHandler(msg -> clientWs.writeTextMessage(msg));
        upstreamWs.binaryMessageHandler(buf -> clientWs.writeBinaryMessage(buf));

        clientWs.closeHandler(
                v -> {
                    metrics.decrementWsConnections();
                    if (!upstreamWs.isClosed()) {
                        upstreamWs.close();
                    }
                });
        upstreamWs.closeHandler(
                v -> {
                    if (!clientWs.isClosed()) {
                        clientWs.close();
                    }
                });

        clientWs.exceptionHandler(
                err -> {
                    LOG.debugf("Client WebSocket error: %s", err.getMessage());
                    if (!upstreamWs.isClosed()) upstreamWs.close();
                });
        upstreamWs.exceptionHandler(
                err -> {
                    LOG.debugf("Upstream WebSocket error: %s", err.getMessage());
                    if (!clientWs.isClosed()) clientWs.close();
                });
    }
}
