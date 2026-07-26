package com.benchmark.scg;

import java.time.Duration;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Per-route upstream timeout, mirroring the Loom Gateway benchmark's per-route timeout filter.
 * Wraps the filter chain in a {@link Mono#timeout(Duration)} so an upstream that does not respond
 * within {@code timeout} ms fails the exchange with 504 — symmetric with Loom's {@code
 * HttpProxyHandler} timeout.
 *
 * <p>(Spring Cloud Gateway 4.x removed the built-in per-request timeout filter, so this is a small
 * custom factory rather than the old {@code RequestTimeout} short name.)
 */
@Component
public class TimeoutGatewayFilterFactory
        extends AbstractGatewayFilterFactory<TimeoutGatewayFilterFactory.Config> {

    public TimeoutGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        Duration d = Duration.ofMillis(config.timeout > 0 ? config.timeout : 5000);
        return (exchange, chain) ->
                chain.filter(exchange)
                        .timeout(d)
                        .onErrorResume(
                                java.util.concurrent.TimeoutException.class,
                                ex -> {
                                    ServerWebExchange ex2 = exchange;
                                    if (!ex2.getResponse().isCommitted()) {
                                        ex2.getResponse().setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
                                    }
                                    return ex2.getResponse().setComplete();
                                });
    }

    public static class Config {
        /** Timeout in milliseconds for the upstream response. */
        public int timeout = 5000;

        public Config() {}

        public TimeoutGatewayFilterFactory.Config setTimeout(int timeout) {
            this.timeout = timeout;
            return this;
        }
    }
}
