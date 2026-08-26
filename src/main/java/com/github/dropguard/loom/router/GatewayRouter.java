package com.github.dropguard.loom.router;

import com.github.dropguard.loom.config.RouteConfigProvider;
import com.github.dropguard.loom.exception.ConfigException;
import com.github.dropguard.loom.exception.UpstreamException;
import com.github.dropguard.loom.filter.FilterChains;
import com.github.dropguard.loom.filter.FilterContext;
import com.github.dropguard.loom.metrics.GatewayMetrics;
import com.github.dropguard.loom.model.RouteConfig;

import io.opentelemetry.api.trace.Span;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RouteFilter;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Routes incoming requests and delegates to the appropriate proxy handler. Responsibilities: route
 * matching, filter chain execution, metrics, exception handling.
 */
@ApplicationScoped
public class GatewayRouter {

    private static final Logger LOG = Logger.getLogger(GatewayRouter.class);
    private final FilterChains filterChains;
    private final GatewayMetrics metrics;
    private final HttpProxyHandler httpProxyHandler;
    private final RouteConfigProvider routeConfigProvider;
    private final WebSocketProxyHandler webSocketProxyHandler;
    private final List<String> grayHeaderNames;
    private final GatewayCorsPolicy corsPolicy;

    public GatewayRouter(
            FilterChains filterChains,
            GatewayMetrics metrics,
            HttpProxyHandler httpProxyHandler,
            RouteConfigProvider routeConfigProvider,
            WebSocketProxyHandler webSocketProxyHandler,
            @ConfigProperty(name = "gateway.gray.headers") List<String> grayHeaderNames,
            @ConfigProperty(name = "gateway.cors.enabled") boolean corsEnabled,
            @ConfigProperty(name = "gateway.cors.origins") String corsOrigins,
            @ConfigProperty(name = "gateway.cors.methods") String corsMethods,
            @ConfigProperty(name = "gateway.cors.headers") String corsHeaders,
            @ConfigProperty(name = "gateway.cors.exposed-headers") String corsExposedHeaders,
            @ConfigProperty(name = "gateway.cors.max-age") long corsMaxAge) {
        this.filterChains = filterChains;
        this.metrics = metrics;
        this.httpProxyHandler = httpProxyHandler;
        this.routeConfigProvider = routeConfigProvider;
        this.webSocketProxyHandler = webSocketProxyHandler;
        this.grayHeaderNames = grayHeaderNames != null ? grayHeaderNames : List.of();
        this.corsPolicy =
                new GatewayCorsPolicy(
                        corsEnabled,
                        corsOrigins,
                        corsMethods,
                        corsHeaders,
                        corsExposedHeaders,
                        corsMaxAge);
    }

    @RouteFilter
    void interceptWebSocket(RoutingContext context) {
        if (!isWebSocketUpgrade(context.request())) {
            context.next();
            return;
        }

        if (context.request().path().startsWith("/q/")) {
            context.next();
            return;
        }

        stripClientGrayHeaders(context);

        RouteConfig matchedRoute =
                routeConfigProvider.findMatchingRoute(
                        context.request().path(), context.request().method().toString());
        if (matchedRoute == null) {
            context.response().setStatusCode(404).end("No matching route");
            return;
        }

        LOG.debugf(
                "Matched route: %s -> %s [WebSocket]", matchedRoute.id(), matchedRoute.upstream());

        FilterContext filterContext = new FilterContext(context.request(), context.response());
        filterContext.route(matchedRoute);

        try {
            filterChains
                    .ws()
                    .execute(
                            filterContext,
                            () -> {
                                webSocketProxyHandler.proxy(context, filterContext);
                            });
        } catch (Exception e) {
            LOG.errorf(e, "WebSocket filter chain error");
            if (!context.response().ended()) {
                context.response().setStatusCode(500).end("Internal Server Error");
            }
        }
    }

    @Route(path = "/*")
    @RunOnVirtualThread
    public void handle(RoutingContext context) {
        if (context.request().path().startsWith("/q/")) {
            context.next();
            return;
        }

        stripClientGrayHeaders(context);

        // Surface the OpenTelemetry traceId on the response so callers can
        // correlate their request with gateway logs. Quarkus injects the active
        // span's traceId into the Vert.x context for @Route handlers; we echo it
        // back via a headers-end handler so every response branch (incl. errors)
        // carries it without repeating the write at each return site.
        String traceId = Span.current().getSpanContext().getTraceId();
        if (traceId != null && !traceId.isEmpty()) {
            context.addHeadersEndHandler(
                    v -> {
                        if (!context.response().headers().contains("trace-id")) {
                            context.response().headers().add("trace-id", traceId);
                        }
                    });
        }

        metrics.incrementActiveConnections();
        long startNanos = System.nanoTime();
        String routeId = "unknown";
        int statusCode = 200;

        try {
            // CORS: the framework's quarkus.http.cors does not apply to @Route
            // reactive routes, so the gateway owns CORS header handling here.
            // Preflight (OPTIONS + Access-Control-Request-Method) is answered
            // directly, before route matching, since it is method-agnostic.
            String corsOrigin = context.request().getHeader("Origin");
            if (corsPolicy.isPreflight(
                    corsOrigin,
                    context.request().method().toString(),
                    context.request().getHeader("Access-Control-Request-Method") != null)) {
                corsPolicy.buildHeaders(corsOrigin).forEach(context.response()::putHeader);
                context.response().setStatusCode(200).end();
                return;
            }

            RouteConfig matchedRoute =
                    routeConfigProvider.findMatchingRoute(
                            context.request().path(), context.request().method().toString());
            if (matchedRoute == null) {
                statusCode = 404;
                // DEFECT: a CORS browser request that hits a missing route must
                // still receive the CORS headers, or the browser blocks the
                // (perfectly useful) 404 body. Attach the headers before writing
                // the error response.
                if (corsPolicy.isCorsRequest(corsOrigin)) {
                    corsPolicy.buildHeaders(corsOrigin).forEach(context.response()::putHeader);
                }
                context.response().setStatusCode(404).end("No matching route");
                return;
            }

            // Simple CORS request: attach the allow-origin header; it is sent
            // before the proxy writes the body.
            if (corsPolicy.isCorsRequest(corsOrigin)) {
                corsPolicy.buildHeaders(corsOrigin).forEach(context.response()::putHeader);
            }

            routeId = matchedRoute.id();
            LOG.debugf("Matched route: %s -> %s", routeId, matchedRoute.upstream());

            FilterContext filterContext = new FilterContext(context.request(), context.response());
            filterContext.route(matchedRoute);

            // DEFECT: POST/PUT request bodies were lost because Vert.x only
            // buffers the body when a body handler is registered; reactive
            // filter chains never consumed it, so the proxy forwarded an empty
            // body. This virtual thread blocks on body() and hands the bytes to
            // the proxy via FilterContext.
            Buffer body = readRequestBody(context);
            if (body != null) {
                filterContext.requestBody(body.getBytes());
            }

            filterChains
                    .http()
                    .execute(
                            filterContext,
                            () -> {
                                httpProxyHandler.proxy(context, filterContext);
                            });

            statusCode = context.response().getStatusCode();

        } catch (UpstreamException e) {
            statusCode = e.getStatusCode();
            LOG.warnf(
                    "Upstream error for %s %s: %s",
                    context.request().method(), context.request().path(), e.getMessage());
            if (!context.response().ended()) {
                context.response().setStatusCode(statusCode).end("Bad Gateway");
            }
        } catch (ConfigException e) {
            statusCode = 500;
            LOG.errorf(e, "Configuration error: %s", e.getMessage());
            if (!context.response().ended()) {
                context.response().setStatusCode(500).end("Internal Server Error");
            }
        } catch (Exception e) {
            statusCode = 500;
            LOG.errorf(
                    e,
                    "Unexpected error processing request: %s %s",
                    context.request().method(),
                    context.request().path());
            if (!context.response().ended()) {
                context.response().setStatusCode(500).end("Internal Server Error");
            }
        } finally {
            metrics.recordRequest(routeId, statusCode, System.nanoTime() - startNanos);
            metrics.decrementActiveConnections();
        }
    }

    /**
     * Reads the request body by blocking on the core Future. This runs on a virtual thread ({@link
     * RunOnVirtualThread}), so blocking is cheap and does not pin an event-loop thread. The body is
     * captured BEFORE the filter chain runs and forwarded to upstream via {@link
     * FilterContext#requestBody()}; without this, POST/PUT payloads silently arrive empty at the
     * backend.
     */
    private static Buffer readRequestBody(RoutingContext context) {
        io.vertx.core.http.HttpServerRequest request = context.request();
        if (request == null) {
            return null;
        }
        try {
            return request.body()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.debugf("Failed to read request body: %s", e.getMessage());
            return null;
        }
    }

    private static boolean isWebSocketUpgrade(io.vertx.core.http.HttpServerRequest request) {
        String connection = request.getHeader("Connection");
        String upgrade = request.getHeader("Upgrade");
        return connection != null
                && connection.toLowerCase().contains("upgrade")
                && "websocket".equalsIgnoreCase(upgrade);
    }

    /**
     * Removes any client-supplied gray headers (configured globally via {@code
     * gateway.gray.headers}). Gray routing must be driven only by a trusted layer, so a client must
     * not be able to forge or strip these headers to influence canary routing. They are also not
     * forwarded upstream.
     */
    private void stripClientGrayHeaders(RoutingContext context) {
        for (String name : grayHeaderNames) {
            context.request().headers().remove(name);
        }
    }
}
