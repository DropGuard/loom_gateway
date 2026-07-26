package com.benchmark.scg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Response caching built on SCG's own high-level abstraction: {@link
 * ModifyResponseBodyGatewayFilterFactory}. We don't hand-roll a ServerHttpResponseDecorator — that
 * path is notoriously bypassed by SCG's Netty write path.
 *
 * <p>Two things make the framework's rewrite actually fire: 1. The filter runs with an {@link
 * Ordered} value BEFORE {@link NettyWriteResponseFilter} (WRITE_RESPONSE_FILTER_ORDER - 1), so the
 * body-rewrite decorator is installed before the response is flushed. 2. On a cache miss we call
 * {@code enableBodyCaching(routeId)} so the WebClient routing filter buffers the upstream body,
 * which ModifyResponseBody needs to read/rewrite it.
 *
 * <p>The RewriteFunction just memoizes the bytes; on the next identical request the short-circuit
 * branch returns the cached bytes without calling the upstream. Verified by
 * GatewayScgIntegrationTest#cacheFilter_shortCircuitsSecondRequest.
 */
@Component
public class CacheGatewayFilterFactory
        extends AbstractGatewayFilterFactory<CacheGatewayFilterFactory.Config> {

    private final ModifyResponseBodyGatewayFilterFactory modifyResponseBody;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public CacheGatewayFilterFactory(ModifyResponseBodyGatewayFilterFactory modifyResponseBody) {
        super(Config.class);
        this.modifyResponseBody = modifyResponseBody;
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter delegate =
                modifyResponseBody.apply(
                        c -> {
                            c.setInClass(byte[].class);
                            c.setOutClass(byte[].class);
                            c.setRewriteFunction(
                                    byte[].class,
                                    byte[].class,
                                    (ServerWebExchange exchange, byte[] body) -> {
                                        if (body != null) {
                                            cache.put(
                                                    exchange.getRequest().getURI().getPath(), body);
                                        }
                                        return Mono.just(body);
                                    });
                        });

        return new OrderedCacheFilter(this, delegate, cache);
    }

    public static class Config {}

    /** Test hook: drop the in-memory cache between tests. */
    void clearCache() {
        cache.clear();
    }

    /**
     * Ordered so it installs the body-rewrite decorator before Netty flushes, and calls
     * enableBodyCaching on a miss so ModifyResponseBody can read the body.
     */
    private static final class OrderedCacheFilter implements GatewayFilter, Ordered {
        private final CacheGatewayFilterFactory factory;
        private final GatewayFilter delegate;
        private final Map<String, byte[]> cache;

        OrderedCacheFilter(
                CacheGatewayFilterFactory factory,
                GatewayFilter delegate,
                Map<String, byte[]> cache) {
            this.factory = factory;
            this.delegate = delegate;
            this.cache = cache;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            String key = exchange.getRequest().getURI().getPath();
            byte[] cached = cache.get(key);
            if (cached != null) {
                // Cache hit: short-circuit, never call the upstream.
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                DataBuffer buffer = response.bufferFactory().wrap(cached);
                return response.writeWith(Mono.just(buffer));
            }
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route != null) {
                factory.enableBodyCaching(route.getId());
            }
            return delegate.filter(exchange, chain);
        }

        @Override
        public int getOrder() {
            return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
        }
    }
}
