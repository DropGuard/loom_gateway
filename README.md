# Loom Gateway

A lightweight, high-performance API gateway built on **Quarkus + Vert.x + Java 25 Virtual Threads**.

## Features

- **Virtual Threads**: `@RunOnVirtualThread` enables synchronous-style code on Project Loom virtual threads with high concurrency and low latency
- **Response Streaming**: Vert.x `pipeTo()` streams HTTP responses directly to clients without buffering; responses ≤1MB are cached via buffer-then-send for API acceleration
- **WebSocket Proxy**: Full-duplex WebSocket proxying with subprotocol negotiation, circuit breaker integration, and connection lifecycle management
- **Filter Chain**: Authentication (JWT/HS256), rate limiting, circuit breaking, canary (gray) release, and response caching — executed in priority order
- **Hot Reload**: WatchService monitors `routes.yaml` for changes — no restart required
- **Prometheus Metrics**: Exposes `/q/metrics` and `/q/health/live`
- **K8s-Native**: Resolves backends via K8s DNS; config mounted via ConfigMap

## Quick Start

### 1. Route Configuration

Edit `config/routes.yaml`:

```yaml
routes:
  - id: api-users
    path: /api/users/**
    upstream: user-service.default.svc.cluster.local:8080
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

```bash
mvn clean package -DskipTests
docker build -t loom-gateway .
docker run -p 8080:8080 -v ./config:/config:ro loom-gateway
```

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
| **Avg Latency** | **3.88ms** | 6.51ms | ~1.7x lower latency |
| **Median (P50)** | **3.82ms** | 6.33ms | ~1.7x lower latency |
| **P90 Latency** | **5.90ms** | 10.84ms | ~1.8x lower latency |
| **P95 Latency** | **6.80ms** | 12.54ms | ~1.8x lower latency |
| **P99 Latency** | **9.68ms** | 16.93ms | **Sub-10ms tail latency** |
| **P99.9 Latency** | **14.38ms** | 24.03ms | ~1.7x lower latency |
| **Scenario RPS** | **25,423** | 15,241 | **+67% higher throughput** |
| **Error Rate** | 0.00% | 0.00% | Both 100% reliable |

### 2. JWT Auth & Claims Forwarding (`/bench/auth`)

| Metric | Loom Gateway | Spring Cloud Gateway (4.3) | Advantage |
| :--- | :--- | :--- | :--- |
| **Avg Latency** | **4.27ms** | 7.45ms | ~1.7x lower latency |
| **Median (P50)** | **4.20ms** | 7.28ms | ~1.7x lower latency |
| **P90 Latency** | **6.45ms** | 11.90ms | ~1.8x lower latency |
| **P95 Latency** | **7.31ms** | 13.45ms | ~1.8x lower latency |
| **P99 Latency** | **9.96ms** | 17.15ms | **Sub-10ms tail latency** |
| **P99.9 Latency** | **14.97ms** | 22.56ms | ~1.5x lower latency |
| **Scenario RPS** | **23,122** | 13,342 | **+73% higher throughput** |
| **Error Rate** | 0.00% | 0.00% | Both 100% reliable |

### 3. In-Memory Response Caching (`/bench/cache`)

| Metric | Loom Gateway | Spring Cloud Gateway (4.3) | Advantage |
| :--- | :--- | :--- | :--- |
| **Avg Latency** | **1.51ms** | 2.57ms | ~1.7x lower latency |
| **Median (P50)** | **1.17ms** | 1.48ms | ~1.26x lower latency |
| **P90 Latency** | **3.04ms** | 4.74ms | ~1.55x lower latency |
| **P99 Latency** | **5.95ms** | 8.51ms | ~1.43x lower latency |
| **Scenario RPS** | **58,588** | 37,714 | **+55% higher throughput** |
| **Error Rate** | 0.00% | 0.00% | Both 100% reliable |

> **Key Takeaway:** Under saturated load on 2 cores, Loom Gateway achieves **~1.7x higher throughput** and **sub-10ms P99 tail latency** across all proxy and auth workloads, demonstrating the efficiency of imperative execution on Virtual Threads over reactive stream (`Mono`/`Flux`) operator pipelines. See [benchmark/RESULTS.md](benchmark/RESULTS.md) for in-depth technical analysis and test design details.

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

