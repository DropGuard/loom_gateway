# Benchmark Results & Interpretation

Standardized, time-decoupled benchmark comparison under identical container constraints:
- **Resource limit:** 2 CPU / 512M RAM per pod.
- **Hardware isolation:** CPU pinned via Docker `cpuset` (Cores 0,1 for Gateway, 2,3 for Mock Backend, 4-7 for k6 load generator).
- **JVM settings:** `-Xms256m -Xmx256m -XX:+AlwaysPreTouch -XX:+UseG1GC` on JDK 25.
- **Connection pools:** Both gateways configured with `maxPoolSize = 500`.
- **Methodology:** Dedicated 30s warmup + time-decoupled 30s single-variable isolated runs (Pure Open Proxy -> Pure JWT Auth -> Pure Cache Read).

## Summary Comparison

### 1. Pure Proxy Pass-through (/bench/open)

| Metric | Loom Gateway | Spring Cloud Gateway |
| :--- | :--- | :--- |
| Avg Latency | 3.88ms | 6.51ms |
| Median (P50) | 3.82ms | 6.33ms |
| P90 Latency | 5.90ms | 10.84ms |
| P95 Latency | 6.80ms | 12.54ms |
| P99 Latency | 9.68ms | 16.93ms |
| P99.9 Latency | 14.38ms | 24.03ms |
| Max Latency | 54.22ms | 142.64ms |
| Scenario RPS | 25423 | 15241 |
| Error Rate | 0.00% | 0.00% |

### 2. JWT Auth & Claims Forwarding (/bench/auth)

| Metric | Loom Gateway | Spring Cloud Gateway |
| :--- | :--- | :--- |
| Avg Latency | 4.27ms | 7.45ms |
| Median (P50) | 4.20ms | 7.28ms |
| P90 Latency | 6.45ms | 11.90ms |
| P95 Latency | 7.31ms | 13.45ms |
| P99 Latency | 9.96ms | 17.15ms |
| P99.9 Latency | 14.97ms | 22.56ms |
| Max Latency | 32.93ms | 36.07ms |
| Scenario RPS | 23122 | 13342 |
| Error Rate | 0.00% | 0.00% |

### 3. In-Memory Response Caching (/bench/cache)

| Metric | Loom Gateway | Spring Cloud Gateway |
| :--- | :--- | :--- |
| Avg Latency | 1.51ms | 2.57ms |
| Median (P50) | 1.17ms | 1.48ms |
| P90 Latency | 3.04ms | 4.74ms |
| P95 Latency | 3.75ms | 5.72ms |
| P99 Latency | 5.95ms | 8.51ms |
| P99.9 Latency | 13.32ms | 13.18ms |
| Max Latency | 26.47ms | 103.53ms |
| Scenario RPS | 58588 | 37714 |
| Error Rate | 0.00% | 0.00% |

---

## Technical Insights

### 1. Connection Pool Parity Matters
In earlier uncalibrated test setups, Vert.x's default `maxPoolSize = 5` caused extreme queueing starvation when 100+ concurrent requests hit the gateway, artificially inflating proxy latency to ~58ms. Once calibrated to 500 connections (matching Reactor Netty's defaults), Loom Gateway's true virtual-thread dispatching speed was unlocked, delivering ~4ms average latency at 25.4k RPS.

### 2. Virtual Threads vs Reactive Stream Pipelines on 2 Cores
Both gateways run on JDK 25 and use Netty for non-blocking network I/O.
- **Spring Cloud Gateway** executes deep reactive stream operator pipelines (`Mono`/`Flux`) on Reactor Netty.
- **Loom Gateway** executes straightforward imperative code on Virtual Threads (`@RunOnVirtualThread`) backed by Quarkus & Vert.x.
- Under saturated load on 2 cores, Loom Gateway avoids the allocation, subscription, and callback churn of reactive stream operator chains, yielding **~1.7x higher throughput** and **sub-10ms P99 tail latency**.

