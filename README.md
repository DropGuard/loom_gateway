# Loom Gateway

A lightweight, high-performance API gateway built on **Quarkus + Vert.x + Java 25 Virtual Threads**.

## Features

- **Virtual Threads**: `@RunOnVirtualThread` enables synchronous-style code on Project Loom virtual threads with high concurrency and low latency
- **Response Streaming**: Vert.x `pipeTo()` streams HTTP responses directly to clients without buffering; responses ≤1MB are cached via buffer-then-send for API acceleration
- **WebSocket Proxy**: Full-duplex WebSocket proxying with subprotocol negotiation, circuit breaker integration, and connection lifecycle management
- **Filter Chain**: Authentication (JWT/HS256), rate limiting, circuit breaking, canary (gray) release, and response caching — executed in priority order
- **Hot Reload**: route config is re-read on file change (mtime polling, which also survives Kubernetes ConfigMap symlink rotation) — no restart required
- **Prometheus Metrics**: Exposes `/q/metrics` and `/q/health/live`
- **K8s-Native**: Resolves backends via K8s DNS; config mounted via ConfigMap


## Configuration Details

- **Route Path Patterns**: The gateway automatically escapes regular‑expression metacharacters (`.`, `*`, `+`, `?`, `(`, `)`, `[`, `]`, `{`, `}`, `^`, `$`, `\`, `|`) in the `path` field, so you can safely write versions like `/api/v1.0/**` or `/api/(v2)/**`. Only the two wildcards `*` (single segment) and `**` (zero or more segments) retain their special meaning.

- **Duplicate Route IDs**: If the same `id` appears more than once in `routes.yaml`, the loader rejects the entire update, logs an error, and keeps the previously loaded configuration. This prevents a route from being silently shadowed.

- **Vary Header**: When a response is cached, the gateway sets the `Vary` header to the authentication header that participated in the cache key: `Authorization` for JWT‑protected routes, `X-API-Key` for API‑Key‑protected routes. No `Vary` header is added for routes without authentication.

## Quick Start

> **Note**: Benchmark numbers (throughput, latency, memory) for the rate limiter, cache, and gray‑release filters will be appended after the next benchmark run. See `benchmark/RESULTS.md` for the raw data and update this section accordingly.

### 1. Route Configuration

Edit `config/routes.yaml`:

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
        secret: "your-jwt-secret"
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

### 2. Local Development

```bash
mvn quarkus:dev
```

### 3. Build & Run

```bash
# Requires JDK 25
mvn clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### 4. Docker

mvn clean package -DskipTests
docker build -t loom-gateway .
docker run -p 8080:8080 -v ./config:/config:ro loom-gateway


> **密钥注入**：认证密钥通过环境变量在网关加载 `routes.yaml` 时展开
> (`${JWT_SECRET:}` / `${API_KEY_1:}` / `${API_KEY_2:}`)。未注入时对应路由以
> 空密钥运行，认证请求返回 401/500。本地开发用 `docker-compose up`(内置
> `CHANGE_ME_*` 占位值)；**生产环境请用 Kubernetes Secret (`envFrom.secretRef`)
> 注入真实值，切勿沿用 compose 占位**。`docker-compose.yml` 中的注释同样标明了这一点。

## Benchmark

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

## When NOT to Use This Gateway

This gateway is designed for **internal microservice APIs**. It is **not** suitable for:

- **Identity Provider (IdP)** scenarios — the gateway validates tokens but does not issue them
- **Business-to-Business (B2B)** clients — external partners typically require OAuth2/OIDC, which is not supported
- **Multi-tenant SaaS** — requires complex auth logic beyond JWT/API Key

For these scenarios, consider using Kong, APISIX, or a dedicated identity platform (Auth0, Keycloak).

