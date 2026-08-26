package com.github.dropguard.loom.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Collects and exposes gateway metrics via Micrometer (Prometheus compatible). */
@Singleton
public class GatewayMetrics {

    private final MeterRegistry registry;
    private final Timer requestDuration;
    private final Timer upstreamLatency;
    private final AtomicInteger activeConnCount = new AtomicInteger(0);
    private final AtomicInteger activeWsConnCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Counter> statusCounters = new ConcurrentHashMap<>();
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter rateLimitCounter;
    private final Counter circuitBreakerCounter;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("gateway_active_connections", activeConnCount, AtomicInteger::get)
                .description("Current number of active connections")
                .register(registry);

        Gauge.builder("gateway_websocket_active_connections", activeWsConnCount, AtomicInteger::get)
                .description("Current number of active WebSocket connections")
                .register(registry);

        this.requestDuration =
                Timer.builder("gateway_request_duration_seconds")
                        .description("Request processing duration")
                        .tag("gateway", "java-gateway")
                        .register(registry);

        this.upstreamLatency =
                Timer.builder("gateway_upstream_latency_seconds")
                        .description("Time spent waiting for upstream response")
                        .register(registry);

        this.cacheHitCounter =
                Counter.builder("gateway_cache_hits_total")
                        .description("Total cache hits")
                        .register(registry);

        this.cacheMissCounter =
                Counter.builder("gateway_cache_misses_total")
                        .description("Total cache misses")
                        .register(registry);

        this.rateLimitCounter =
                Counter.builder("gateway_rate_limit_exceeded_total")
                        .description("Total requests rejected by rate limiter")
                        .register(registry);

        this.circuitBreakerCounter =
                Counter.builder("gateway_circuit_breaker_triggered_total")
                        .description("Total requests rejected by an OPEN circuit breaker")
                        .register(registry);
    }

    public void incrementActiveConnections() {
        activeConnCount.incrementAndGet();
    }

    public void decrementActiveConnections() {
        activeConnCount.decrementAndGet();
    }

    public void incrementWsConnections() {
        activeWsConnCount.incrementAndGet();
    }

    public void decrementWsConnections() {
        activeWsConnCount.decrementAndGet();
    }

    public void recordRequest(String route, int statusCode, Duration duration) {
        if (duration != null) {
            recordRequest(route, statusCode, duration.toNanos());
        }
    }

    public void recordRequest(String route, int statusCode, long durationNanos) {
        requestDuration.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);

        String key = route + ":" + statusCode;
        statusCounters
                .computeIfAbsent(
                        key,
                        k ->
                                Counter.builder("gateway_requests_total")
                                        .description("Total number of requests processed")
                                        .tag("route", route)
                                        .tag("status", String.valueOf(statusCode))
                                        .register(registry))
                .increment();
    }

    public void recordUpstreamLatency(Duration latency) {
        if (latency != null) {
            recordUpstreamLatency(latency.toNanos());
        }
    }

    public void recordUpstreamLatency(long latencyNanos) {
        upstreamLatency.record(latencyNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void recordCacheHit() {
        cacheHitCounter.increment();
    }

    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }

    public void recordRateLimitExceeded() {
        rateLimitCounter.increment();
    }

    public void recordCircuitBreakerTriggered() {
        circuitBreakerCounter.increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
