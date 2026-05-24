# Java High-Performance Gateway

A lightweight, high-performance API gateway built on Quarkus + Java 25 + Netty.

## Features

- **Virtual Threads**: Uses `@RunOnVirtualThread` for a high-performance synchronous programming model
- **Response Streaming**: Vert.x `pipeTo()` streams backend responses directly to clients without buffering in JVM heap
- **Filter Chain**: Authentication, rate limiting, circuit breaking, canary (gray) release, and caching — executed in priority order
- **Hot Reload**: WatchService monitors `routes.yaml` for changes — no restart required
- **Prometheus Metrics**: Exposes `/q/metrics` and `/q/health/live`
- **K8s-Native**: Resolves backends via K8s DNS; config mounted via ConfigMap

## Build

```bash
mvn clean package -DskipTests
```

## Local Development

```bash
mvn quarkus:dev
```

## Docker Deployment

```bash
docker compose up -d
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

## Metrics

Visit `http://localhost:8080/q/metrics` for Prometheus-format metrics.

## Health Check

Visit `http://localhost:8080/q/health/live` to check gateway liveness.
