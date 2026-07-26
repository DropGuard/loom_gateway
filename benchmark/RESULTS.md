# Benchmark Results & Interpretation

Latest run: mixed-load (proxied open/auth + dedicated cache scenario, 100 VUs each),
2 CPU / 512M pod limit (a typical k8s pod size). Both gateways 0% errors.

See `results/compare.md` for the raw table. Key takeaways below.

## Structure: two symmetric external projects

`benchmark/` holds two sibling projects, both external to (and parallel with) the
main `src/`:

- `gateway-scg` — standalone SCG app, consumes `spring-cloud-starter-gateway`.
- `gateway-loom` — standalone Quarkus app, consumes the main `loom-gateway` as a
  Maven dependency. Quarkus discovers the library's `@Singleton`/`@ApplicationScoped`
  beans (filter chain, router) via `quarkus.index-dependency`; `GatewayRouter`'s
  `@Route(path="/*")` then handles all traffic with no routing code in the app.

This symmetry matters: each side is measured as a *consumer of its framework*,
not as the framework's own source tree. The Loom side proves `loom-gateway` is
itself a reusable library, not just an application.


## Both gateways sit on Netty — this is a programming-model comparison

- **SCG**: Spring WebFlux on **Reactor Netty**. The reactive operator chain is
  scheduled directly on the Netty event loop; it never leaves the 2 cores.
- **Loom**: Quarkus → **Vert.x**, which is *also* built on Netty (the
  `vert.x-eventloop-thread` workers in the logs are Netty loops). Virtual threads
  run the command-style filter logic *above* Vert.x, so there is one
  thread-boundary hop between the event loop and the virtual thread.

So the measured difference is the cost of the **programming model on a shared
Netty transport**, not a transport difference.

## Cache-hit path — Loom ~4x faster

| | Loom | SCG |
|---|---|---|
| cache med | 1.97ms | 5.06ms |
| cache P90 | 3.60ms | 53.11ms |
| cache P95 | 5.17ms | 64.59ms |

Neither side re-hits the upstream; both write the response bytes through Netty.
Loom returns the cached bytes straight from the virtual thread with no framework
wrapping. SCG, even on a cache hit, still runs the body through
`ModifyResponseBody`'s `Mono` + byte[] decode/encode cycle — the *only* pattern
that works in SCG 4.3 (see the note in the README). The high SCG P90 is reactive
scheduling variance, not upstream latency.

## Proxied pass-through (open/auth) — SCG lower latency at 2 cores

| | Loom | SCG |
|---|---|---|
| open avg | 58.88ms | 43.21ms |
| open med | 57.62ms | 21.37ms |
| auth avg | 59.21ms | 44.88ms |
| auth med | 58.14ms | 22.90ms |

SCG's chain is *native* to the event loop (zero hop); Loom pays a per-request hop
across the event-loop/virtual-thread boundary, which surfaces as extra latency
when 2 cores are contended by ~300 concurrent VUs. Reactive scheduling is still
sharp on exactly this small-core, pure-I/O forwarding workload.

## Bottom line

On a realistic 2-core pod, Loom's virtual threads win decisively on the
zero-upstream cache-served path (shedding reactive operator overhead), while SCG's
event-loop-native model stays ahead on pure proxying. Neither side is specially
favored — the comparison is honest about both outcomes.

## SCG gotcha captured by the integration test

SCG 4.3 response-body caching is NOT the popular `ServerHttpResponseDecorator` +
`writeWith` snippet: that decorator is bypassed by `NettyWriteResponseFilter`, so
the cache silently never populates. The working approach delegates to
`ModifyResponseBodyGatewayFilterFactory`, runs `Ordered` before the Netty write
filter, and calls `enableBodyCaching(routeId)`. This is documented in full in
`benchmark/gateway-scg/README.md` (the SCG project's own readme, not the Loom
repo) — including the integration tests that pin the behavior.
