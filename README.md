# Loom Gateway

A lightweight, high-performance API gateway built on **Quarkus + Vert.x + Java 25 Virtual Threads**.

## Features

- **Virtual Threads**: `@RunOnVirtualThread` enables synchronous-style code on Project Loom virtual threads with high concurrency and low latency
- **Response Streaming**: Vert.x `pipeTo()` streams HTTP responses directly to clients without buffering; responses ≤1MB are cached via buffer-then-send for API acceleration
- **WebSocket Proxy**: Full-duplex WebSocket proxying with subprotocol negotiation, circuit breaker integration, and connection lifecycle management
- **Filter Chain**: Authentication (JWT/HS256), rate limiting, circuit breaking, canary (gray) release, and response caching — executed in priority order
- **Hot Reload**: route config is re-read on file change (mtime polling, which also survives Kubernetes ConfigMap symlink rotation) — no restart required
- **Prometheus Metrics**: Exposes `/q/metrics` and `/q/health/live`
- **Kubernetes-ready**: Upstreams can use cluster DNS names; routes mount from a ConfigMap with symlink-aware hot reload; readiness/liveness probes and graceful shutdown are built in
- **Runs standalone**: The same fast-jar/native binary also runs via `java -jar` or Docker, with the config path set by `gateway.config.path` / `GATEWAY_CONFIG_PATH`


## Configuration Details

- **Route Path Patterns**: The gateway automatically escapes regular‑expression metacharacters (`.`, `*`, `+`, `?`, `(`, `)`, `[`, `]`, `{`, `}`, `^`, `$`, `\`, `|`) in the `path` field, so you can safely write versions like `/api/v1.0/**` or `/api/(v2)/**`. Only the two wildcards `*` (single segment) and `**` (zero or more segments) retain their special meaning.

- **Duplicate Route IDs**: If the same `id` appears more than once in `routes.yaml`, the loader rejects the entire update, logs an error, and keeps the previously loaded configuration. This prevents a route from being silently shadowed.

- **Vary Header**: When a response is cached, the gateway sets the `Vary` header to the authentication header that participated in the cache key: `Authorization` for JWT‑protected routes, `X-API-Key` for API‑Key‑protected routes. No `Vary` header is added for routes without authentication.

- **Credential Handling**: The gateway terminates authentication. On routes that enforce JWT or API-key auth, client-supplied `Authorization`/`X-API-Key` are not forwarded; downstream receives `X-User-Id` (the JWT `sub`, or the SHA-256 hash of the API key) plus any configured `X-Claim-*` headers. Client-supplied `X-User-Id`/`X-Claim-*` are always dropped. Routes with `auth.type: none` pass client credentials through unchanged. Cookies are forwarded unchanged; the gateway does not authenticate via cookies.

- **Config Source & Startup**: `routes.yaml` is the only configuration source today (local file or Kubernetes ConfigMap). A missing or invalid file aborts startup; after a successful start, a failed hot reload keeps the last known-good route table.

## Quick Start

### 1. Run with Docker Compose (fastest)

```bash
docker compose up --build
curl http://localhost:8080/q/health/live
# {"status":"UP"}
```

`docker-compose.yml` supplies local-only `CHANGE_ME_*` placeholder secrets so the gateway can start. Never reuse them outside local development.

To run the published image directly instead, pass the secrets referenced by `config/routes.yaml` — required secrets are validated at load time, so empty values abort startup:

```bash
docker run -d --name loom-gateway \
  -p 8080:8080 \
  -e JWT_SECRET='local-only-jwt-secret-at-least-32-chars!!' \
  -e API_KEY_1='local-only-api-key-1' \
  -e API_KEY_2='local-only-api-key-2' \
  -v ./config:/config:ro \
  ghcr.io/dropguard/loom_gateway:latest
```

The image is published to GHCR for tags matching `v*` or `[0-9]+.*`; see [Docker Image](#docker-image).

### 2. Build and Run Locally (JVM mode)

**Dev mode** (hot reload, but slower startup and no AOT optimization — **benchmark numbers are not measured in this mode**):

```bash
# Local-only secrets: the bundled config/routes.yaml requires them at load time.
export JWT_SECRET='local-only-jwt-secret-at-least-32-chars!!'
export API_KEY_1='local-only-api-key-1'
export API_KEY_2='local-only-api-key-2'
mvn quarkus:dev
```

> ⚠️ **Dev mode vs production fast-jar**:
> - Dev startup is 5–10x slower (Live Reload, DevUI, debug agent)
> - First-request latency is higher (lazy initialization)
> - **Steady-state throughput and latency are essentially the same**, but **startup and first-request experience differ substantially**
> - Use fast-jar or native mode to reproduce the benchmark numbers below.

**Production mode** (fast-jar, fully JIT-optimized, same mode as the benchmark):

```bash
mvn clean package -DskipTests
# Same three secrets as dev mode (or real values from your secret store)
export JWT_SECRET='local-only-jwt-secret-at-least-32-chars!!'
export API_KEY_1='local-only-api-key-1'
export API_KEY_2='local-only-api-key-2'
java -jar target/quarkus-app/quarkus-run.jar
```

### 3. Native Build (fastest startup, tens of ms, ~30 MB)

```bash
mvn clean package -Pnative -DskipTests
# The artifact name includes the version, e.g. loom-gateway-1.0.0-SNAPSHOT-runner
./target/loom-gateway-*-runner
```

### 4. Configure routes.yaml

Edit `config/routes.yaml` (or start from [`config/routes.example.yaml`](config/routes.example.yaml)):

```yaml
routes:
  - id: api-users
    path: /api/users/**
    methods: [GET, POST, PUT, DELETE]
    upstream: user-service.default.svc.cluster.local:8080
    filters:
      auth:
        type: jwt
        required: true
        secret: "${JWT_SECRET:}"
      rateLimit:
        limit: 1000
        windowMs: 1000
      circuitBreaker:
        failureThreshold: 5
        resetTimeoutMs: 30000
      cache:
        enabled: true
        ttlSeconds: 60
```

> **Secret injection**: Auth secrets are expanded from environment variables when the gateway loads `routes.yaml` (`${JWT_SECRET:}`, `${API_KEY_1:}`, `${API_KEY_2:}`). A required secret that resolves to empty is rejected at load time: the initial load fails fast and the gateway refuses to start, while a bad hot reload keeps the last known-good configuration. Local development can use the `CHANGE_ME_*` placeholders in `docker-compose.yml`; **production must inject real values from a Kubernetes Secret (`envFrom.secretRef`) and must never reuse the compose placeholders**.

## Benchmark

> **Note**: the benchmark numbers below were measured in **fast-jar mode** (`mvn clean package -DskipTests`); `quarkus:dev` has significantly different startup behavior.

Standardized comparison under identical container conditions:
- **Pod Limit:** 2 CPU / 512M RAM
- **JVM & GC:** `-Xms256m -Xmx256m -XX:+AlwaysPreTouch -XX:+UseG1GC` (JDK 25)
- **CPU Pinning:** Gateway on `cpuset: "0,1"`, Mock Backend on `cpuset: "2,3"`, k6 on `cpuset: "4,5,6,7"`
- **Connection Pool:** 500 max pool size (matching Reactor Netty)
- **Methodology:** 30s warmup + 30s time-decoupled isolated scenario runs

### 1. Pure Proxy Pass-through (`/bench/open`)

| Metric | Loom Gateway | Spring Cloud Gateway (4.3) | Advantage |
| :--- | :--- | :--- | :--- |
| **Avg Latency** | **4.18ms** | 7.16ms | ~1.7x lower latency |
| **Median (P50)** | **4.09ms** | 6.95ms | ~1.7x lower latency |
| **P90 Latency** | **6.37ms** | 11.73ms | ~1.8x lower latency |
| **P95 Latency** | **7.30ms** | 13.32ms | ~1.8x lower latency |
| **P99 Latency** | **10.16ms** | 17.53ms | ~1.7x lower latency |
| **P99.9 Latency** | **15.40ms** | 23.94ms | ~1.6x lower latency |
| **Scenario RPS** | **23,594** | 13,860 | **+70% higher throughput** |
| **Error Rate** | 0.00% | 0.00% | Both 100% reliable |

### 2. JWT Auth & Claims Forwarding (`/bench/auth`)

| Metric | Loom Gateway | Spring Cloud Gateway (4.3) | Advantage |
| :--- | :--- | :--- | :--- |
| **Avg Latency** | **4.58ms** | 7.99ms | ~1.7x lower latency |
| **Median (P50)** | **4.48ms** | 7.79ms | ~1.7x lower latency |
| **P90 Latency** | **6.89ms** | 12.57ms | ~1.8x lower latency |
| **P95 Latency** | **7.81ms** | 14.17ms | ~1.8x lower latency |
| **P99 Latency** | **10.61ms** | 18.11ms | ~1.7x lower latency |
| **P99.9 Latency** | **15.96ms** | 24.12ms | ~1.5x lower latency |
| **Scenario RPS** | **21,558** | 12,431 | **+73% higher throughput** |
| **Error Rate** | 0.00% | 0.00% | Both 100% reliable |

### 3. In-Memory Response Caching (`/bench/cache`)

| Metric | Loom Gateway | Spring Cloud Gateway (4.3) | Advantage |
| :--- | :--- | :--- | :--- |
| **Avg Latency** | **1.74ms** | 2.67ms | ~1.5x lower latency |
| **Median (P50)** | **1.40ms** | 1.52ms | ~1.1x lower latency |
| **P90 Latency** | **3.35ms** | 4.61ms | ~1.4x lower latency |
| **P99 Latency** | **6.70ms** | 8.73ms | ~1.3x lower latency |
| **Scenario RPS** | **52,273** | 36,284 | **+44% higher throughput** |
| **Error Rate** | 0.00% | 0.00% | Both 100% reliable |

> **Key Takeaway:** Under saturated load on 2 cores, Loom Gateway achieves **~1.7x higher throughput** and **~1.7x lower latency** across proxy and auth workloads, with cache reads ~1.5x faster end-to-end — demonstrating the efficiency of imperative execution on Virtual Threads over reactive stream (`Mono`/`Flux`) operator pipelines. See [benchmark/RESULTS.md](benchmark/RESULTS.md) for in-depth technical analysis and test design details.

To reproduce:

```bash
cd benchmark
python run.py
```

## Docker Image

Images are built and pushed to GitHub Container Registry by [`.github/workflows/docker-publish.yml`](.github/workflows/docker-publish.yml) when a tag matching `v*` or `[0-9]+.*` is pushed (or manually via `workflow_dispatch`):

```bash
docker pull ghcr.io/dropguard/loom_gateway:latest
```

Tags pushed for a release are the Git tag itself plus `latest`.

## Observability & Health

- **Prometheus Metrics:** Visit `http://localhost:8080/q/metrics` for Prometheus-format metrics.
- **Health Check:** Visit `http://localhost:8080/q/health/live` to check gateway liveness.

## Design Philosophy

### What This Gateway Can Do

| Capability | Large Files | Notes |
|------------|-------------|-------|
| **HTTP Proxy** | ✅ | Any HTTP method, any size — streamed via Vert.x `pipeTo()` |
| **WebSocket Proxy** | ✅ | Full-duplex, any message size |
| **Auth / Rate Limit / Circuit Breaker** | ✅ | Operates on request headers only, response-agnostic |
| **Response Caching** | ⚠️ API only | ≤1MB responses only, large files bypass cache automatically |

### Caching Strategy

The gateway uses a layered approach for response caching:

| Response Size | Strategy | Description |
|---------------|----------|-------------|
| ≤ 1MB | Buffer-then-Send | Full response buffered → cached → sent to client |
| > 1MB | Stream (pipeTo) | Directly piped from upstream to client, bypasses cache |

This ensures API responses benefit from caching while large responses still use efficient streaming without excessive JVM heap usage.

Cache memory is bounded by **total payload bytes**, not entry count: the cache defaults to a 64 MiB budget (response body + key + ~256 B per-entry overhead) and evicts via W-TinyLFU. A single response larger than the budget is not cached at all, so a handful of large entries can never exhaust the heap.

## When NOT to Use This Gateway

This gateway is designed for **internal microservice APIs**. It is **not** suitable for:

- **Identity Provider (IdP)** scenarios — the gateway validates tokens but does not issue them
- **Business-to-Business (B2B)** clients — external partners typically require OAuth2/OIDC, which is not supported
- **Multi-tenant SaaS** — requires complex auth logic beyond JWT/API Key

For these scenarios, consider using Kong, APISIX, or a dedicated identity platform (Auth0, Keycloak).

