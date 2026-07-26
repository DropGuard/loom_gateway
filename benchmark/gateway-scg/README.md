# Spring Cloud Gateway — benchmark app

This is the Spring Cloud Gateway (SCG) half of the symmetric Loom Gateway vs SCG
benchmark. It boots a full gateway against an in-process WireMock upstream and,
in the Docker benchmark, against an nginx mock backend. The filter chain is
feature-for-feature matched to the Loom side (JWT auth, fixed-window rate limit,
claims forwarding, per-route timeout, response headers, response caching).

See `../RESULTS.md` for the measured numbers and their interpretation.

## The response-cache implementation trap (documented here, not in the Loom repo)

SCG 4.3 response caching — and *any* response-body rewrite — is **not** the
pattern you will find by searching. The widely-copied snippet looks like this:

```java
ServerHttpResponse original = exchange.getResponse();
ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(original) {
    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return DataBufferUtils.join(body).flatMap(aggregated -> {
            byte[] bytes = readAndRelease(aggregated);
            cache.put(key, bytes);          // <- side effect
            return super.writeWith(Mono.just(wrap(bytes)));
        });
    }
};
return chain.filter(exchange.mutate().response(decorator).build());
```

**This does not work.** The `writeWith` override is bypassed by
`NettyWriteResponseFilter`'s write path, so the cache is *silently never
populated*. We proved it with an integration test
(`GatewayScgIntegrationTest#cacheFilter_shortCircuitsSecondRequest`) that counts
how many times the upstream is hit via WireMock: the naive version hits the
upstream on **both** requests, so the cache never short-circuits. (A `Flux.map`
variant of the same idea fails identically.)

### What actually works

Three things must hold for the rewrite/cache to fire:

1. **Delegate to the framework's own `ModifyResponseBodyGatewayFilterFactory`**
   instead of hand-rolling a `ServerHttpResponseDecorator`. Do the byte
   memoization inside its `RewriteFunction` (which returns `Mono<byte[]>`).
2. **Run `Ordered` before `NettyWriteResponseFilter`**
   (`NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1`). Otherwise Netty
   flushes the response before the decorator is even installed.
3. **Call `enableBodyCaching(routeId)`** on a cache miss. SCG buffers the
   upstream body into the exchange only when this is set; without it, the
   `RewriteFunction` receives nothing.

`CacheGatewayFilterFactory` in `src/main/java/com/benchmark/scg/` is the verified
implementation. Its `apply()` returns an `Ordered` filter: on a hit it writes the
cached bytes directly; on a miss it calls `enableBodyCaching` and delegates to the
`ModifyResponseBody` filter, which memoizes the body in its `RewriteFunction`.

Two integration tests pin this down so the regression can't return silently:

- `cacheFilter_shortCircuitsSecondRequest` — the second identical request must
  hit the upstream **exactly once** (proves the cache populates and short-circuits).
- `cacheFilter_returnsSameBody` — the served body matches the upstream body.

> Note: these tests hit a **real port** via `java.net.http.HttpClient`, not
> `WebTestClient`'s direct handler call. `WebTestClient` exercises a
> `MockServerHttpResponse` that also bypasses the Netty write path, giving a
> false pass/fail. The same trap, different layer.

## Other SCG 4.3 gotchas that the integration tests guard

- **`RequestTimeout` filter was removed** in 4.x. Configuring a route with a
  `name: RequestTimeout` filter makes the context fail to start. The benchmark
  uses a custom `Timeout` filter instead (asserted by `slowUpstreamReturns504…`).
- **Response header injection via a `getHeaders()` decorator is bypassed** by the
  Netty write filter, just like the cache decorator. The `ResponseHeaders` filter
  must set headers on the response before the write stage, not via a decorator
  `getHeaders()` override (asserted by `openRouteAddsBenchmarkResponseHeaders`).
- **Claims forwarding depends on `JwtAuth` publishing a claims attribute** that
  `ClaimsForward` reads. A broken wiring means `X-User-Id` is absent downstream
  (asserted by `authRouteForwardsSubAsXUserIdToUpstream`).
