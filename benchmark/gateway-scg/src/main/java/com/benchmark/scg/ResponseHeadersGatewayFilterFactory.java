package com.benchmark.scg;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Adds benchmark response headers, mirroring the Loom Gateway's response-header
 * injection. {@code X-Gateway} tags the implementation, {@code X-Request-Id}
 * correlates the request, and {@code X-Process-Time} records end-to-end gateway
 * latency in milliseconds.
 *
 * Headers are set before the response is committed (no body capture), symmetric
 * with Loom's {@code selectClientHeaders} and avoiding the reactive-body
 * interception problem that makes response caching awkward on SCG.
 */
@Component
public class ResponseHeadersGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ResponseHeadersGatewayFilterFactory.Config> {

    public ResponseHeadersGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        String gateway = config.gateway != null ? config.gateway : "scg";
        boolean addRequestId = config.requestId != null ? config.requestId : true;
        boolean addProcessTime = config.processTime != null ? config.processTime : true;

        return (exchange, chain) -> {
            Instant start = Instant.now();
            String requestId = exchange.getRequest().getId();
            ServerHttpResponse response = exchange.getResponse();

            // Add headers before the response is committed. NettyWriteResponseFilter
            // reads exchange.getResponse().getHeaders() at commit time, so setting
            // them here (before the chain runs) is the reliable injection point —
            // no body capture needed, symmetric with Loom's selectClientHeaders.
            response.getHeaders().set("X-Gateway", gateway);
            if (addRequestId) {
                response.getHeaders().set("X-Request-Id", requestId);
            }
            // X-Process-Time is filled in just before commit, when the upstream
            // has responded, so the latency figure is accurate.
            return chain.filter(exchange)
                    .then(Mono.fromRunnable(() -> response.getHeaders().set(
                            "X-Process-Time",
                            Duration.between(start, Instant.now()).toMillis() + "ms")));
        };
    }

    public static class Config {
        public String gateway = "scg";
        public Boolean requestId = true;
        public Boolean processTime = true;

        public Config() {
        }

        public ResponseHeadersGatewayFilterFactory.Config setGateway(String gateway) {
            this.gateway = gateway;
            return this;
        }

        public ResponseHeadersGatewayFilterFactory.Config setRequestId(Boolean requestId) {
            this.requestId = requestId;
            return this;
        }

        public ResponseHeadersGatewayFilterFactory.Config setProcessTime(Boolean processTime) {
            this.processTime = processTime;
            return this;
        }
    }
}
