package com.gateway.router;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gateway.exception.ConfigException;
import com.gateway.exception.UpstreamException;
import com.gateway.filter.CircuitBreakerFilter;
import com.gateway.filter.FilterContext;
import com.gateway.metrics.GatewayMetrics;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;

/**
 * Locks HttpProxyHandler's failure-mode contract: a route with no resolved
 * upstream must fail loudly (ConfigException), and an unreachable upstream must
 * surface as UpstreamException (502) rather than leaking the raw cause. These
 * are the error paths the integration tests only exercise indirectly.
 */
@ExtendWith(MockitoExtension.class)
class HttpProxyHandlerTest {

    @Mock
    Vertx vertx;
    @Mock
    HttpClient httpClient;
    @Mock
    GatewayMetrics metrics;
    @Mock
    CircuitBreakerFilter circuitBreakerFilter;

    private HttpProxyHandler handler() {
        when(vertx.createHttpClient(any(io.vertx.core.http.HttpClientOptions.class))).thenReturn(httpClient);
        return new HttpProxyHandler(vertx, metrics, circuitBreakerFilter, 5000);
    }

    /** A RoutingContext whose request reports a GET method (avoids NPE in proxy). */
    private RoutingContext mockContext() {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rc.request()).thenReturn(req);
        when(req.method()).thenReturn(HttpMethod.GET);
        return rc;
    }

    @Test
    void noUpstream_throwsConfigException() {
        HttpProxyHandler handler = handler();
        // No route, no gray upstream -> effectiveUpstream is null.
        FilterContext ctx = new FilterContext(mock(), mock());

        ConfigException ex = assertThrows(ConfigException.class,
                () -> handler.proxy(mockContext(), ctx));
        assertTrue(ex.getMessage().contains("No upstream"));
    }

    @Test
    void upstreamUnreachable_throwsUpstreamException() {
        HttpProxyHandler handler = handler();
        // request() fails (connection refused / DNS) -> surfaced as 502.
        when(httpClient.request(any(), anyInt(), anyString(), anyString()))
                .thenReturn(Uni.createFrom().failure(
                        new java.net.ConnectException("Connection refused")));

        RoutingContext rc = mockContext();
        // The proxy forwards incoming headers, so the request must supply some.
        when(rc.request().headers()).thenReturn(io.vertx.core.MultiMap.caseInsensitiveMultiMap());

        FilterContext ctx = new FilterContext(mock(), mock());
        // Give the route an upstream so we reach the request() call.
        ctx.route(routeWithUpstream("backend:8080"));

        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> handler.proxy(rc, ctx));
        assertEquals(502, ex.getStatusCode());
        // raw cause must not escape; it is wrapped into the gateway exception.
        assertFalse(ex.getCause() instanceof UpstreamException);
    }

    private com.gateway.model.RouteConfig routeWithUpstream(String upstream) {
        return new com.gateway.model.RouteConfig(
                "test-route", "/test/**", java.util.List.of("GET"), upstream,
                com.gateway.model.RouteConfig.FilterConfig.EMPTY);
    }
}
