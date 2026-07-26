package com.github.dropguard.loom.router;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.dropguard.loom.exception.ConfigException;
import com.github.dropguard.loom.exception.UpstreamException;
import com.github.dropguard.loom.filter.CircuitBreakerFilter;
import com.github.dropguard.loom.filter.FilterContext;
import com.github.dropguard.loom.metrics.GatewayMetrics;
import com.github.dropguard.loom.model.RouteConfig;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Locks HttpProxyHandler's failure-mode contract. The happy path (header transformation,
 * status/body forwarding) is covered without mocking by ProxyHeadersTest / UpstreamAddressTest;
 * here we only need Mockito to force the two error branches that require a fake upstream client: -
 * no resolved upstream -> ConfigException - upstream unreachable -> UpstreamException(502), raw
 * cause wrapped
 */
@ExtendWith(MockitoExtension.class)
class HttpProxyHandlerTest {

    @Mock Vertx vertx;
    @Mock HttpClient httpClient;
    @Mock GatewayMetrics metrics;
    @Mock CircuitBreakerFilter circuitBreakerFilter;

    private HttpProxyHandler handler() {
        when(vertx.createHttpClient(any(io.vertx.core.http.HttpClientOptions.class)))
                .thenReturn(httpClient);
        return new HttpProxyHandler(vertx, metrics, circuitBreakerFilter, 5000);
    }

    /**
     * A minimal GET context — enough for the error paths that bail out before touching the response
     * or forwarding headers.
     */
    private RoutingContext minimalContext() {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rc.request()).thenReturn(req);
        when(req.method()).thenReturn(HttpMethod.GET);
        return rc;
    }

    @Test
    void noUpstream_throwsConfigException() {
        HttpProxyHandler handler = handler();
        FilterContext ctx = new FilterContext(mock(), mock());

        ConfigException ex =
                assertThrows(ConfigException.class, () -> handler.proxy(minimalContext(), ctx));
        assertTrue(ex.getMessage().contains("No upstream"));
    }

    @Test
    void upstreamUnreachable_throwsUpstreamException() {
        HttpProxyHandler handler = handler();
        // request() fails (connection refused / DNS) -> surfaced as 502.
        when(httpClient.request(any(), anyInt(), anyString(), any()))
                .thenReturn(
                        Uni.createFrom()
                                .failure(new java.net.ConnectException("Connection refused")));

        RoutingContext rc = minimalContext();
        FilterContext ctx = new FilterContext(mock(), mock());
        ctx.route(routeWithUpstream("backend:8080"));

        UpstreamException ex = assertThrows(UpstreamException.class, () -> handler.proxy(rc, ctx));
        assertEquals(502, ex.getStatusCode());
        // raw cause must not escape; it is wrapped into the gateway exception.
        assertFalse(ex.getCause() instanceof UpstreamException);
    }

    private RouteConfig routeWithUpstream(String upstream) {
        return new RouteConfig(
                "test-route", "/test/**", List.of("GET"), upstream, RouteConfig.FilterConfig.EMPTY);
    }
}
