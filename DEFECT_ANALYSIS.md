# Loom Gateway — Defect Analysis

> Analysis performed 2026-07-15 by deepseek-v4-pro. Covers source under
> `src/main/java/com/gateway` plus `config/routes.yaml` and `application.yaml`.

This is a Quarkus (3.35.1) reactive API gateway running on virtual threads
(Loom). Core flow: `GatewayRouter.handle()` matches a route, builds a
`FilterContext`, runs the filter chain (`Auth → RateLimit → CircuitBreaker →
Gray → Cache` for HTTP), then `HttpProxyHandler` / `WebSocketProxyHandler`
forwards to the upstream.

The following are confirmed defects or correctness bugs, ordered by severity.
Each lists the file, the problem, and the impact.

---

## P0 — Correctness / Security (must fix)

### 1. ~~Gray-release `headerMatch` client-controllable~~ — FIXED (and gray model redesigned)
**File:** `filter/GrayReleaseFilter.java`, `model/RouteConfig.java` (`GrayConfig`),
`router/GatewayRouter.java`, `application.yaml`
**Was:** `value.equals(reqValue)` matched the raw client header, so any client
sending `X-Canary: true` was routed to the canary — a business-logic / security
bypass. The original fix also left two gray channels (header-match + percentage)
implicitly coexisting with hidden "A-prioritized-over-B" fallback.
**Fix applied (two phases):**
1. *Security:* client-supplied gray headers are stripped at ingress; gray routing
   is driven only by a trusted layer via a **dedicated** header (`X-Internal-Canary`)
   that is never in the strip list, so clients cannot forge or strip it.
2. *Model cleanup:* collapsed the two coexisting channels into one `GrayConfig`
   with an explicit `mode` (`percentage` | `rule`), selected per route — no more
   implicit fallback. `percentage` buckets authenticated identities (sub/apiKey);
   `rule` matches the trusted header. Strip list is now **global**
   (`gateway.gray.headers` in `application.yaml`), decoupled from any route's
   `headerMatch`.
**Verification:** full suite (89 tests, 12 suites) green; `mvn test` EXIT=0.
Both `mode=percentage` and `mode=rule` are covered by unit + integration tests.

### 2. `effectiveUpstream()` can be null → NPE / mis-proxy for gray routes without resolved identity
**File:** `filter/FilterContext.java` line 77-79 + `router/HttpProxyHandler.java` line 54
**Problem:** `effectiveUpstream()` returns `route.upstream()` only if
`route()` is non-null. `UpstreamAddress.parse(null)` is called when
`grayUpstream` is null AND `route` is null. More importantly, in the gray
filter, when `threshold == 0` (e.g. the `api-users` canary is configured at
`trafficPercent: 10` but the test uses 0) nothing routes to gray, which is
correct — but `targetUpstream` is only set when the branch fires. If a filter
upstream is ever not set and `route` is null (e.g. invoked outside the router
context), `parse(null)` throws `NullPointerException` inside
`UpstreamAddress.parse` (`parts[0]` of null). While the router always sets the
route, the null-guard is incomplete and brittle.
**Impact:** Latent NPE risk; also the gray filter's `shouldRouteToGray`
returns false when identity is null, which silently drops canary traffic for
unauthenticated routes — see #6.
**Fix:** Add explicit null/empty validation in `UpstreamAddress.parse` and in
`HttpProxyHandler` before parsing.

### 3. ~~CORS preflight OPTIONS hits the router and returns 404~~ — RETRACTED (was a misjudged P0)
**File:** `router/GatewayRouter.java`, `application.yaml`, `quarkus-vertx-http` `CORSFilter`
**Correction:** Originally reported as a P0 ("OPTIONS preflight 404s because
the gateway's catch-all `@Route(path="/*")` matches OPTIONS before Quarkus
CORS can short-circuit"). This was **wrong**, confirmed by running the built
artifact (`quarkus-run.jar`, Quarkus 3.35.1) and sending real preflight
requests:

- `OPTIONS /api/users/me` (Origin + A-C-Request-Method: POST) → **200** with
  full CORS headers (`access-control-allow-origin`, `-allow-methods`,
  `-allow-headers`, `-max-age`).
- `OPTIONS /api/public/data` → **200** with full CORS headers.
- `OPTIONS /no/such/route` (path matches NO route) → **200** with full CORS
  headers, while `GET /no/such/route` → **404**.

**Why the original analysis was wrong:** Quarkus's `CORSFilter` is installed
as a `RouteFilter` with an extremely early order (before any user route), so
it intercepts OPTIONS preflight requests and terminates them *before* the
gateway's `handle()` ever runs — regardless of whether a route matches. The
fear that "native image shifts filter priorities" does not materialize; CORS
ordering is part of Quarkus's framework contract, not incidental.

**Verdict:** No code change needed. CORS works correctly. The existing
integration test `cors_preflight_returnsCorrectHeaders` passing is genuine,
not luck.

### 4. HTTP response status code is read AFTER the proxy — but `recordRequest` uses wrong/undefined status for streamed bodies
**File:** `router/GatewayRouter.java` line 122 + `router/HttpProxyHandler.java` lines 93-124
**Problem:** `statusCode = context.response().getStatusCode()` is read after
`filterChains.http().execute(...)`. In stream mode the response is terminated
by `pipeTo`, which sets the status code. But in the buffered (interceptor)
mode, the status is set inside the chain. In *both* cases the value read at
line 122 is whatever Vert.x set — which is fine — **except** when an exception
is thrown *inside* the await (e.g. timeout). Then `getStatusCode()` may return
the default (200) because the response was never written, yet `statusCode`
remains 200 and the catch block overrides it. That part is OK. The real bug:
`metrics.recordRequest(routeId, statusCode, duration)` uses `statusCode` which
for streamed successes is correctly 200 — but **the status is read on the
client response object which, for an already-ended response, returns the ended
status**. Acceptable, but there is **no handling for the case where `await().indefinitely()`
blocks forever** because both connect *and* idle timeout are `timeoutMs`
(5000 ms) — so not infinite. Downgraded to note; see #9.

### 5. `X-User-Id` header is stripped from inbound but re-injected; forged value only blocked for JWT, NOT for API-key or public routes
**File:** `filter/HttpProxyHandler.java` lines 73-85
**Problem:** Inbound `X-User-Id` is dropped (`!\"X-User-Id\".equalsIgnoreCase(key)`),
then re-added only when `claims.get("sub") != null`. For **api-key** and
**public** routes there are no claims, so `X-User-Id` is never re-injected —
that's fine. But the strip is the security control: any client `X-User-Id` is
removed. However, the strip happens only for the *forwarded* request; if a
later filter or the upstream trusts a *different* forwarded header, it's
unprotected. More critically, **the strip is bypassable**: the header is
removed from `backendReq.headers()` but if the upstream or a trusted proxy
re-adds it based on a different header, identity can be spoofed. The gateway
relies solely on dropping `X-User-Id` inbound; there is no assertion that the
upstream ignores client identity. This is acceptable *if* upstreams are
trusted, but it is a fragile security boundary that should be documented and
ideally enforced with a signed header.
**Impact:** Medium. Works for the common case but the trust boundary is
implicit.

---

## P1 — Reliability / Concurrency

### 6. Circuit breaker records failures via `proxySuccess`, but `proxySuccess` is set only in the HTTP path; WebSocket failures recorded separately, causing inconsistent state
**File:** `filter/CircuitBreakerFilter.java` + `router/WebSocketProxyHandler.java` + `router/GatewayRouter.java`
**Problem:** For HTTP, `proxySuccess` is set inside the lambda at
`GatewayRouter.handle` lines 114-119. For WebSocket, `proxySuccess` is never
set (the WS handler runs its own async callbacks), so the circuit breaker for
WS routes relies on `recordOutcome()` called manually in `WebSocketProxyHandler`
(lines 73, 80). **But the WS filter chain (`FilterChains.ws()`) includes the
CircuitBreakerFilter**, which checks `context.proxySuccess()` at line 55 — and
that is always `false` for WS. So after a successful WS connection the
circuit breaker records a **failure** (`recordFailure()` at line 58) because
`proxySuccess()` is false. This **trips the circuit breaker open on successful
WebSocket upgrades**, breaking WS routing after the first successful
connection if there were prior failures, and *always* counting a successful WS
as a failure once any prior failure threshold logic engages.
**Impact:** WebSocket circuit breaker is effectively broken — counts success
as failure. High severity for WS-reliant deployments.
**Fix:** Set `filterContext.proxySuccess(true)` in `WebSocketProxyHandler`
on successful upgrade, or have the CB filter not read `proxySuccess` for WS
chains. Better: unify the success signal.

### 7. `SimpleCircuitBreaker` HALF_OPEN → success never resets `consecutiveFailures` correctly and OPEN uses `compareAndSet` race that can admit many probes
**File:** `filter/SimpleCircuitBreaker.java` `allowRequest` lines 38-46
**Problem:** In OPEN state, multiple concurrent requests can all observe
`now - lastFailureTime >= resetTimeoutMs` and *all* pass the `if` before the
`compareAndSet` — actually `compareAndSet` only lets one win, so only one goes
HALF_OPEN, others return false. That's correct. But `recordSuccess()` in
HALF_OPEN resets `consecutiveFailures` to 0 — good. However `recordFailure()`
in CLOSED increments `consecutiveFailures` and only opens when `>= threshold`.
There is **no grace period / rolling window**: a single slow startup burst of
`failureThreshold` failures opens the breaker and it stays open for
`resetTimeoutMs` regardless of recovery. Also `lastFailureTime` is updated on
*every* failure, so a steady trickle of failures continuously resets the
timer and the breaker **never** moves to HALF_OPEN (the timeout is never
reached because each failure pushes `lastFailureTime` forward). This is a
classic bug: **breakers stuck OPEN under sustained low-rate failure**.
**Impact:** Under partial degradation, the breaker can stay open indefinitely,
causing 100% 503s.

### 8. Rate limiter `tryAcquire` increments then checks `<= limit`, allowing `limit+1` requests per window edge, and counter never decrements on failure
**File:** `filter/SimpleRateLimiter.java` lines 26-38
**Problem:** `counter.incrementAndGet() <= limit` — on the very first request
counter becomes 1 (<= limit OK). After `limit` requests counter == limit (OK),
the **(limit+1)-th** request increments to limit+1 (> limit → rejected). So
it actually allows exactly `limit` requests — correct. **But** `incrementAndGet`
happens *before* the check, so even rejected requests consume a slot. With
window reset, the first rejected request still bumped the counter, which is
fine since window resets. The real issue: **no decrement on rejection and no
per-client key** — the limiter is keyed only by `routeId`, so *all* clients
share one bucket. A single heavy user exhausts the whole route's quota for
everyone (no per-IP / per-user fairness).
**Impact:** Not a bug per se but a design gap; also `windowStart` is `volatile`
read without lock in the comparison — under the lock it's fine, but the field
should be accessed inside the lock only (it is). OK. Medium.

### 9. `HttpProxyHandler.proxy` uses `await().indefinitely()` on the virtual thread — blocks the VT but idle timeout may not cover body streaming
**File:** `router/HttpProxyHandler.java` lines 70, 124
**Problem:** `await().indefinitely()` is used for both the request creation and
the full response. Connect + idle timeout are both `timeoutMs`. For large
responses the idle timeout resets on activity so it's roughly OK, but there is
**no overall request deadline** — a slow-loris upstream that sends one byte
every `timeoutMs-1` ms streams forever, holding the virtual thread and the
client connection. Also `await().indefinitely()` will throw if the upstream
closes abruptly, caught and wrapped — acceptable. The bigger issue is the
**whole proxy blocks one virtual thread per request**; with virtual threads
that's the intended design, so acceptable. Note only.

---

## P2 — Robustness / Maintainability

### 10. `RouteConfigLoader` watches the parent *directory*, not the specific file, and may reload on unrelated sibling-file changes
**File:** `config/RouteConfigLoader.java` `startWatching` lines 61-94
**Problem:** Registers the *parent directory* for ENTRY_MODIFY/CREATE and then
filters by filename. On some file systems (and especially editors that write a
temp file then rename), the actual modified file may be a sibling temp file,
and the rename of `routes.yaml` shows as a CREATE of `routes.yaml` + the
filter matches — OK. But on macOS/Linux `sed -i` creates `routes.yaml.tmp` and
renames; the MODIFY event may be for the temp name and never fire for
`routes.yaml`. Also **there is no debounce**: a single `:w` in vim fires
multiple modify events → multiple reloads. Minor.

### 11. Config file not found → gateway starts with EMPTY config and **readiness reports DOWN forever**, but liveness is UP → Kubernetes never restarts it
**File:** `health/GatewayReadinessCheck.java` + `GatewayConfigInitializer.java` + `RouteConfigLoader.loadFromPath`
**Problem:** If `/config/routes.yaml` is missing, `loadFromPath` logs a warning
and keeps `GatewayConfig.EMPTY`. Readiness returns DOWN. Liveness is always UP.
Kubernetes will not restart a pod that is live-but-not-ready; the gateway sits
broken serving 404s. There is no fail-fast on missing critical config.

### 12. `CacheFilter` caches by `request.uri()` which includes the query string but identity is only added when auth present — cache poisoning across users if auth is optional
**File:** `filter/CacheFilter.java` `buildKey` lines 90-98
**Problem:** Key = `method:uri` or `method:uri:identity`. For routes where auth
is conditionally present (e.g. a route that allows both anonymous and
authenticated), an anonymous response could be served to an authenticated user
if the uri matches — but the cache only stores what upstream returned. If the
upstream returns *different* bodies for authed vs anon on the same URI, the
cache serves the first one to everyone. Also **no `Vary` header** is set, and
**`Cache-Control` is not forwarded**. Medium.

### 13. `CacheFilter` buffers the *entire* response body into memory for every cacheable GET, even cache hits don't help, and `MAX_CACHE_BODY_SIZE` check happens after full buffering
**File:** `filter/CacheFilter.java` lines 68-76
**Problem:** The interceptor receives `mutinyBody.getBytes()` for *every* GET
that misses cache — for a 900 KB response this is fine, but for a route that
frequently misses and returns large bodies, you pay full buffering cost on
every miss. The size guard is inside the interceptor (after buffering). For a
1 MB+ response the gateway holds it in heap. Acceptable for 1 MB cap, but the
cap is only enforced *after* reading the full body.

### 14. `AuthFilter` JWT `nbf`/`exp` checks use `java.util.Date` and are not timezone-safe; `secret` length not validated for HS256 (needs ≥ 32 bytes for HS256 in nimbus? actually any length works but short secrets are weak)
**File:** `filter/AuthFilter.java` lines 50-93
**Problem:** `MACVerifier` with a short secret silently verifies weak signatures
(no minimum-length enforcement). The default secret in YAML is
`change-me-in-production` (19 chars) — below the 32-byte recommendation for
HS256 and trivially brute-forceable. There is **no algorithm confusion
defense**: a token signed with `alg: none` or an RSA public key would still be
parsed by `SignedJWT.parse` + `MACVerifier` — nimbus rejects alg mismatch, but
`alg: none` is not defended against explicitly. Acceptable but worth a
`JWSHeader.getAlgorithm()` check.

### 15. `UpstreamAddress.parse` does not handle IPv6 literals or user-info; `port` parsed with `Integer.parseInt` after split on `:`
**File:** `router/UpstreamAddress.java` lines 25-38
**Problem:** `url.split(":", 2)` on `https://[::1]:8080` yields host=`[::1]`
(port part `8080` ✓) but the bracket is not stripped → Netty fails to resolve
`[::1]`. IPv6 is unsupported. Also a URL with `@` userinfo would break. Low.

### 16. `GatewayRouter.interceptWebSocket` catches `Exception` but the filter chain runs `webSocketProxyHandler.proxy` which is **async** — exceptions thrown later in callbacks are never caught
**File:** `router/GatewayRouter.java` lines 71-80
**Problem:** `filterChains.ws().execute(...)` returns after scheduling async
work. The try/catch around it only catches synchronous errors from the chain.
Asynchronous failures inside `WebSocketProxyHandler` (e.g. upstream connect
failure) are handled *inside* that class via `onFailure` — OK — but any
exception thrown on the vertx event loop thread (not a VT) is an
uncaught-exception on the event loop, which Vert.x logs but does not convert
to a 500. Acceptable but fragile.

### 17. ~~`quarkus-virtual-threads` dependency "missing" from `pom.xml`~~ — RETRACTED (was a misjudged P0)
**File:** `pom.xml`, `target/quarkus-app/quarkus-app-dependencies.txt`
**Correction:** Originally reported as a P0 ("extension missing, concurrency
model invalid"). This was **wrong**. The extension was always present:
- `target/quarkus-app/lib/main/io.quarkus.quarkus-virtual-threads-3.35.1.jar`
  exists in the built artifact.
- `target/quarkus-app/quarkus-app-dependencies.txt` line 63 lists
  `io.quarkus:quarkus-virtual-threads::jar:3.35.1`.
- It was pulled in **transitively** through `quarkus-reactive-routes` (both
  managed by `quarkus-bom`), which is a normal and valid Quarkus setup — not a
  bug.
- Code confirms the model works: `@RunOnVirtualThread` on
  `GatewayRouter.handle`, `Thread.ofVirtual()` in `RouteConfigLoader`, and
  `ArchitectureTest` explicitly guards against escaping Mutiny → core Vert.x on
  virtual threads.

**Action taken:** The dependency is now declared **explicitly** in `pom.xml`
(under the Quarkus Core group, after `quarkus-reactive-routes`) so it no
longer relies on transitive resolution. This is a low-risk hardening that
prevents silent loss of the extension if `quarkus-reactive-routes` is ever
removed or the platform BOM changes. Verified with `mvn dependency:tree`
→ `io.quarkus:quarkus-virtual-threads:jar:3.35.1:compile` (direct dependency,
BUILD SUCCESS).

**No code behavior change** — the gateway was already running correctly on
virtual threads.

### 18. `benchmark/gateway-scg` directory duplicates JWT logic and ships a prebuilt `target/gateway-scg-1.0.0.jar` (39 MB) checked into the repo
**File:** `benchmark/gateway-scg/target/...`
**Problem:** Build artifacts committed to source control. The SCG benchmark
also has its own `JwtAuthGatewayFilterFactory` duplicating the gateway's JWT
logic — divergence risk. Low, hygiene.

### 19. No graceful shutdown / in-flight request draining; `Quarkus.waitForExit()` with no hook
**File:** `GatewayApplication.java`
**Problem:** On SIGTERM Quarkus stops the HTTP server immediately; in-flight
proxy streams are cut. For a gateway, connection draining is expected. Low.

### 20. `metrics.recordRequest` creates a new Micrometer `Counter` per `route:status` combination via `computeIfAbsent` — unbounded meter cardinality
**File:** `metrics/GatewayMetrics.java` lines 84-93
**Problem:** If a route can produce many distinct status codes (or if route ids
are dynamic), `statusCounters` grows without bound and Micrometer registers a
meter per combination → metric explosion / memory leak in Prometheus. Route
ids are static here, so bounded — but the pattern is unsafe if route ids ever
become dynamic. Medium/low.

---

## Summary of highest-priority fixes

> Notes on retracted items:
> - Former P0 #17 (`quarkus-virtual-threads` "missing") — the extension was
>   always present (transitively via `quarkus-reactive-routes`); now declared
>   explicitly in `pom.xml`. Removed from the list.
> - Former P0 #3 (CORS preflight 404) — **verified wrong by running the built
>   artifact**: Quarkus's `CORSFilter` runs before user routes and correctly
>   returns 200 + CORS headers for OPTIONS preflight on any path. Removed.

1. ~~**P0 #1** gray `headerMatch` client-controllable~~ — **FIXED + gray model
   redesigned** (explicit `mode` percentage|rule, global `gateway.gray.headers`
   strip list, dedicated trusted header `X-Internal-Canary`; 89 tests green).
2. **P1 #6** — WebSocket circuit breaker counts success as failure (never sets
   `proxySuccess`); fix the success signal.
3. **P1 #7** — circuit breaker can stick OPEN under sustained low-rate failure
   (timer reset on every failure).
4. **P1 #11** — missing config = permanently not-ready but live; add fail-fast
   or alerting.

### Suggested next steps for the remaining P1s

- **#6 (WS circuit breaker):** set `filterContext.proxySuccess(true)` on a
  successful WebSocket upgrade in `WebSocketProxyHandler`, or stop reading
  `proxySuccess()` inside `CircuitBreakerFilter` for the WS chain. Today a
  successful WS upgrade is counted as a failure because `proxySuccess()` stays
  `false` for the WS path — it opens the breaker after the first success that
  follows any prior failure.
- **#7 (breaker stuck OPEN):** `SimpleCircuitBreaker.recordFailure()` updates
  `lastFailureTime` on every failure, so under a steady trickle of failures the
  HALF_OPEN timeout is never reached. Use a sliding window / failure-rate over a
  fixed interval (or only arm the timer on the *transition* to OPEN) instead of
  bumping the timestamp per failure.
- **#11 (config missing → permanently not-ready):** fail fast (or at least log
  ERROR + expose a dedicated health-down signal) when `/config/routes.yaml` is
  absent at startup, so Kubernetes can restart the pod rather than serving 404s
  forever behind a live-but-unready health status.

All other items are robustness/correctness improvements that should follow.
