# Loom Gateway

A lightweight, high-performance API gateway built on **Quarkus + Vert.x + Java 25 Virtual Threads**.

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

## Features

- **Virtual Threads**: `@RunOnVirtualThread` enables synchronous-style code on Project Loom virtual threads
- **Response Streaming**: Vert.x `pipeTo()` streams any HTTP response directly to clients without buffering; responses ≤1MB are cached via buffer-then-send for API acceleration
- **WebSocket Proxy**: Full-duplex WebSocket proxying with subprotocol negotiation, circuit breaker integration, and connection lifecycle management
- **Filter Chain**: Authentication (JWT/HS256), rate limiting, circuit breaking, canary (gray) release, and response caching — executed in priority order
- **Hot Reload**: WatchService monitors `routes.yaml` for changes — no restart required
- **Prometheus Metrics**: Exposes `/q/metrics` and `/q/health/live`
- **K8s-Native**: Resolves backends via K8s DNS; config mounted via ConfigMap

## Build & Run

```bash
# Requires JDK 25
mvn clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

## Local Development

```bash
mvn quarkus:dev
```

## Docker

The Dockerfile is runtime-only (no build stage) — build locally first, then package:

```bash
mvn clean package -DskipTests
docker build -t loom-gateway .
docker run -p 8080:8080 -v ./config:/config:ro loom-gateway
```

## Route Configuration

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

## Benchmark: Loom Gateway vs Spring Cloud Gateway

Controlled comparison under identical conditions: same k6 load script, same nginx mock backend, same resource limits (2 CPU / 512M), Docker containers.

| Metric | Loom Gateway | Spring Cloud Gateway |
|--------|-------------|---------------------|
| open avg | 6.65ms | 14.91ms |
| open med | 5.01ms | 4.06ms |
| open P90 | 10.34ms | 66.19ms |
| open P95 | 10.87ms | 76.34ms |
| auth avg | 7.48ms | 15.78ms |
| auth med | 5.76ms | 4.36ms |
| auth P90 | 10.49ms | 68.27ms |
| auth P95 | 11.02ms | 77.03ms |
| RPS | 8772 | 4037 |
| Error rate | 0% | 0% |

> **Note:** SCG is a full-featured production gateway with extensive built-in filters, service discovery, and Spring ecosystem integration. This project is a minimal implementation. The comparison demonstrates virtual thread scheduling characteristics under load, not a like-for-like product comparison. Absolute values reflect Docker container networking, not bare-metal performance.

To reproduce:

```bash
cd benchmark
python run.py
```

## Metrics

Visit `http://localhost:8080/q/metrics` for Prometheus-format metrics.

## Health Check

Visit `http://localhost:8080/q/health/live` to check gateway liveness.
