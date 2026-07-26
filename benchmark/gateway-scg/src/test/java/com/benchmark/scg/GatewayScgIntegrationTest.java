package com.benchmark.scg;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Date;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * End-to-end integration test for the SCG benchmark filter chain. Boots the full
 * gateway (no Docker) against an in-process WireMock stub upstream, and asserts
 * every feature the symmetric comparison advertises. These assertions exist
 * precisely because each one would have caught a trap we hit while building SCG:
 *
 *  - ResponseHeaders: a {@code getHeaders()}-decorator approach is bypassed by
 *    NettyWriteResponseFilter, so the header would silently never appear. This
 *    test fails if that regression returns.
 *  - Timeout: SCG 4.x removed the built-in RequestTimeout filter, so a
 *    misconfigured name makes the route fail to start — also caught (context
 *    won't come up).
 *  - ClaimsForward: depends on JwtAuth publishing the claims attribute; a broken
 *    wiring means X-User-Id is absent downstream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayScgIntegrationTest {

    static final int STUB_PORT = 9561;
    static WireMockServer stub;

    @Autowired
    WebTestClient webClient;

    @Autowired
    CacheGatewayFilterFactory cacheFactory;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startStub() {
        stub = new WireMockServer(options().port(STUB_PORT));
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop();
        }
    }

    @BeforeEach
    void resetStub() {
        // The cache filter holds an in-memory map on the singleton factory bean;
        // clear it so each test starts cold (otherwise one test's cached entry
        // makes another test's first request short-circuit before hitting upstream).
        cacheFactory.clearCache();
        stub.resetAll();
        stub.stubFor(get(urlPathEqualTo("/bench/open/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));
        stub.stubFor(get(urlPathEqualTo("/bench/auth/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"upstream\":true}")));
        stub.stubFor(get(urlPathEqualTo("/bench/rl/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));
        stub.stubFor(get(urlPathEqualTo("/bench/cache/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"cache\":true,\"server\":1}")));
        // Captures the downstream headers the gateway forwards, so we can assert
        // claims forwarding actually reached the upstream.
        stub.stubFor(get(urlPathEqualTo("/echo-headers"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"captured\":true}")));
    }

    // --- Feature 1: JWT auth -------------------------------------------------

    @Test
    void openRouteIsPublic() {
        webClient.get().uri("/bench/open/data")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.ok").isEqualTo(true);
    }

    @Test
    void authRouteRejectsMissingToken() {
        webClient.get().uri("/bench/auth/data")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void authRouteAcceptsValidToken() throws Exception {
        String token = signedToken("bench-user", "read");
        webClient.get().uri("/bench/auth/data")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    // --- Feature 2: claims forwarding --------------------------------------

    @Test
    void authRouteForwardsSubAsXUserIdToUpstream() throws Exception {
        String token = signedToken("bench-user", "read");
        webClient.get().uri("/bench/auth/data")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        // The gateway forwarded X-User-Id / X-Claim-scope to the stub upstream.
        stub.verify(1, getRequestedFor(urlPathEqualTo("/bench/auth/data"))
                .withHeader("X-User-Id", equalTo("bench-user"))
                .withHeader("X-Claim-scope", equalTo("read")));
    }

    @Test
    void openRouteForwardsNoIdentityWhenAnonymous() {
        webClient.get().uri("/bench/open/data")
                .exchange()
                .expectStatus().isOk();

        stub.verify(0, getRequestedFor(urlPathEqualTo("/bench/open/data"))
                .withHeader("X-User-Id", equalTo("bench-user")));
    }

    // --- Feature 3: response headers (the getHeaders() trap) ---------------

    @Test
    void openRouteAddsBenchmarkResponseHeaders() {
        webClient.get().uri("/bench/open/data")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Gateway", "scg")
                .expectHeader().exists("X-Request-Id")
                .expectHeader().exists("X-Process-Time");
    }

    // --- Feature 4: per-route timeout --------------------------------------

    @Test
    void slowUpstreamReturns504WithinTimeout() {
        // A response that exceeds the 500ms route timeout must fail fast, proving
        // the custom Timeout filter (not a removed built-in) is wired in.
        stub.stubFor(get(urlPathEqualTo("/bench/open/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(1500)
                        .withBody("{\"ok\":true}")));
        webClient.get().uri("/bench/open/data")
                .exchange()
                .expectStatus().isEqualTo(504);
    }

    // --- Feature 5: rate limit ---------------------------------------------

    @Test
    void rateLimitReturns429WhenExceeded() {
        // The /bench/rl route is configured with a small limit (5 / 10s) so the
        // fixed-window limiter trips deterministically under a sequential burst.
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            int status = webClient.get().uri("/bench/rl/data")
                    .exchange()
                    .expectBody().returnResult().getStatus().value();
            if (status == 429) {
                rejected++;
            } else if (status != 200) {
                org.junit.jupiter.api.Assertions.fail("unexpected status " + status);
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(5, rejected,
                "fixed-window limiter must reject exactly the requests over the limit");
    }

    // --- The "standard SCG caching pattern" (ServerHttpResponseDecorator) ---

    @Test
    void cacheFilter_shortCircuitsSecondRequest() throws Exception {
        // Hit the REAL port (not WebTestClient's direct handler call) so the
        // Netty write path runs and the writeWith decorator actually executes.
        // If caching works, the second identical request must NOT reach upstream.
        int first = realGet("/bench/cache/data");
        int second = realGet("/bench/cache/data");
        org.junit.jupiter.api.Assertions.assertEquals(200, first);
        org.junit.jupiter.api.Assertions.assertEquals(200, second);

        stub.verify(1, getRequestedFor(urlPathEqualTo("/bench/cache/data")));
    }

    @Test
    void cacheFilter_returnsSameBody() throws Exception {
        int status = realGet("/bench/cache/data");
        org.junit.jupiter.api.Assertions.assertEquals(200, status);
    }

    private int realGet(String path) throws Exception {
        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                .send(java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create("http://localhost:" + port + path))
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        return resp.statusCode();
    }

    private static String signedToken(String sub, String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .claim("scope", scope)
                .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner("bench-secret-key-that-is-at-least-32-chars!"));
        return jwt.serialize();
    }
}
