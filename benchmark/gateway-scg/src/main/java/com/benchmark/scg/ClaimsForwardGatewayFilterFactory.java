package com.benchmark.scg;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Injects the authenticated subject (and selected claims) as downstream request headers, mirroring
 * the Loom Gateway benchmark's {@code X-User-Id} injection.
 *
 * <p>The paired {@code JwtAuth} filter parses the JWT and stores the claims map under {@code
 * claims} on the exchange; this factory reads that map and copies the configured claims into
 * outgoing headers. Symmetric with Loom's {@code ProxyHeaders.buildForwardHeaders} so the
 * comparison stays honest.
 */
@Component
public class ClaimsForwardGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ClaimsForwardGatewayFilterFactory.Config> {

    /** Exchange attribute the JwtAuth filter populates with the claims map. */
    public static final String CLAIMS_ATTR = "claims";

    public ClaimsForwardGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        List<String> claims = config.claims != null ? config.claims : List.of("sub");
        return (exchange, chain) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> claimsMap =
                    (Map<String, Object>) exchange.getAttribute(CLAIMS_ATTR);
            if (claimsMap == null || claimsMap.isEmpty()) {
                // No authenticated identity (e.g. the open route) — forward as-is.
                return chain.filter(exchange);
            }

            ServerHttpRequest mutated =
                    exchange.getRequest()
                            .mutate()
                            .headers(
                                    headers -> {
                                        for (String claim : claims) {
                                            Object value = claimsMap.get(claim);
                                            if (value != null) {
                                                // Loom maps "sub" -> X-User-Id; mirror that
                                                // exactly.
                                                String header =
                                                        "sub".equalsIgnoreCase(claim)
                                                                ? "X-User-Id"
                                                                : "X-Claim-" + claim;
                                                headers.set(header, value.toString());
                                            }
                                        }
                                    })
                            .build();

            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    public static class Config {
        /** Claim names to forward downstream (default: sub only). */
        public List<String> claims = List.of("sub");

        public Config() {}

        public ClaimsForwardGatewayFilterFactory.Config setClaims(List<String> claims) {
            this.claims = claims;
            return this;
        }
    }
}
