package com.gateway.router;

import com.gateway.cache.LocalCacheManager.CacheEntry;
import com.gateway.config.RouteConfigLoader;
import com.gateway.filter.CacheFilter;
import com.gateway.filter.CircuitBreakerFilter;
import com.gateway.filter.FilterChain;
import com.gateway.filter.FilterContext;
import com.gateway.model.RouteConfig;
import io.quarkus.vertx.web.Route;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.HttpClientRequest;
import io.vertx.mutiny.core.http.HttpClientResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GatewayRouter {

    private static final Logger LOG = Logger.getLogger(GatewayRouter.class);
    private final HttpClient httpClient;
    private final FilterChain filterChain;

    @Inject
    RouteConfigLoader routeConfigLoader;

    @Inject
    CircuitBreakerFilter circuitBreakerFilter;

    @Inject
    CacheFilter cacheFilter;

    public GatewayRouter(Vertx vertx, FilterChain filterChain) {
        this.httpClient = vertx.createHttpClient();
        this.filterChain = filterChain;
    }

    @Route(path = "/*")
    @RunOnVirtualThread
    public void handle(RoutingContext context) {
        if (context.request().path().startsWith("/q/")) {
            context.next();
            return;
        }

        String routeId = null;
        try {
            RouteConfig matchedRoute = routeConfigLoader.findMatchingRoute(
                context.request().path(),
                context.request().method().toString()
            );
            if (matchedRoute == null) {
                context.response().setStatusCode(404).end("No matching route");
                return;
            }

            routeId = matchedRoute.id();
            LOG.debugf("Matched route: %s -> %s", routeId, matchedRoute.upstream());

            FilterContext filterContext = new FilterContext(context.request(), context.response());
            filterContext.route(matchedRoute);

            if (!filterChain.execute(filterContext)) {
                return;
            }

            CacheEntry cachedEntry = filterContext.getAttribute("cachedResponse");
            if (cachedEntry != null) {
                writeCachedResponse(context, cachedEntry);
                return;
            }

            boolean success = proxy(context, filterContext);
            circuitBreakerFilter.recordOutcome(routeId, success);

            if (success) {
                cacheFilter.storeResponse(filterContext);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Error processing request: %s %s",
                context.request().method(), context.request().path());
            if (!context.response().ended()) {
                context.response().setStatusCode(502).end("Bad Gateway");
            }
            if (routeId != null) {
                circuitBreakerFilter.recordOutcome(routeId, false);
            }
        }
    }

    private void writeCachedResponse(RoutingContext context, CacheEntry entry) {
        context.response().setStatusCode(entry.statusCode());
        if (entry.contentType() != null) {
            context.response().headers().set("content-type", entry.contentType());
        }
        context.response().end(io.vertx.core.buffer.Buffer.buffer(entry.body()));
        LOG.debugf("Served cached response (%d bytes)", entry.body().length);
    }

    private boolean proxy(RoutingContext context, FilterContext filterContext) throws Exception {
        io.vertx.core.http.HttpServerRequest incomingReq = context.request();
        HttpMethod method = HttpMethod.valueOf(incomingReq.method().name());

        String upstreamUrl = filterContext.effectiveUpstream();
        boolean ssl = false;
        if (upstreamUrl.startsWith("https://")) {
            upstreamUrl = upstreamUrl.substring(8);
            ssl = true;
        } else if (upstreamUrl.startsWith("http://")) {
            upstreamUrl = upstreamUrl.substring(7);
        }

        String[] parts = upstreamUrl.split(":", 2);
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : (ssl ? 443 : 80);

        String uri = incomingReq.path();
        String queryString = incomingReq.query();
        if (queryString != null) {
            uri += "?" + queryString;
        }

        String effectiveUpstream = filterContext.effectiveUpstream();
        LOG.debugf("Forwarding to: %s:%d%s", host, port, uri);

        try {
            // 1. Prepare backend request
            HttpClientRequest backendReq = httpClient.request(method, port, host, uri)
                .await().indefinitely();

            // Copy headers from client to backend request, strip X-User-Id to prevent spoofing
            incomingReq.headers().forEach(entry -> {
                String key = entry.getKey();
                if (!isHopByHopHeader(key)
                        && !"content-length".equalsIgnoreCase(key)
                        && !"X-User-Id".equalsIgnoreCase(key)) {
                    backendReq.headers().add(key, entry.getValue());
                }
            });

            // Inject authenticated user identity for upstream services
            java.util.Map<String, Object> claims = filterContext.getAttribute("claims");
            if (claims != null && claims.get("sub") != null) {
                backendReq.headers().set("X-User-Id", claims.get("sub").toString());
            }

            // 2. Send request body and pipe response in one reactive chain.
            //    The .chain() callback runs on the event loop BEFORE response body
            //    data is processed, so pipeTo can capture the full body stream.
            io.vertx.core.buffer.Buffer body = context.body() != null ? context.body().buffer() : null;
            io.vertx.core.http.HttpServerResponse clientResp = context.response();

            backendReq.send(
                io.vertx.mutiny.core.buffer.Buffer.newInstance(body != null ? body : io.vertx.core.buffer.Buffer.buffer())
            ).chain(backendRes -> {
                backendRes.headers().forEach(entry -> {
                    if (!isHopByHopHeader(entry.getKey())) {
                        clientResp.headers().add(entry.getKey(), entry.getValue());
                    }
                });
                clientResp.setStatusCode(backendRes.statusCode());
                LOG.debugf("Upstream response: %d", backendRes.statusCode());
                return backendRes.pipeTo(
                    io.vertx.mutiny.core.http.HttpServerResponse.newInstance(clientResp)
                );
            }).await().indefinitely();
            return true;

        } catch (Exception e) {
            LOG.errorf(e, "Proxy failed for %s", effectiveUpstream);
            io.vertx.core.http.HttpServerResponse clientResp = context.response();
            if (!clientResp.ended()) {
                if (e instanceof java.util.concurrent.TimeoutException
                        || e instanceof io.netty.handler.timeout.TimeoutException) {
                    clientResp.setStatusCode(504).end("Gateway Timeout");
                } else {
                    clientResp.setStatusCode(502).end("Bad Gateway");
                }
            }
            return false;
        }
    }

    private boolean isHopByHopHeader(String name) {
        return switch (name.toLowerCase()) {
            case "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                 "te", "trailers", "transfer-encoding", "upgrade", "host" -> true;
            default -> false;
        };
    }
}
