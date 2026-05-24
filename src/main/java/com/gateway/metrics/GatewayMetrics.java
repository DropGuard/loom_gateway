package com.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects and exposes gateway metrics via Micrometer (Prometheus compatible).
 */
@Singleton
public class GatewayMetrics {

    private final MeterRegistry registry;
    private final Timer requestDuration;
    private final AtomicInteger activeConnCount = new AtomicInteger(0);
    private final AtomicInteger activeWsConnCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Counter> statusCounters = new ConcurrentHashMap<>();

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("gateway_active_connections", activeConnCount, AtomicInteger::get)
                .description("Current number of active connections")
                .register(registry);

        Gauge.builder("gateway_websocket_active_connections", activeWsConnCount, AtomicInteger::get)
                .description("Current number of active WebSocket connections")
                .register(registry);

        this.requestDuration = Timer.builder("gateway_request_duration_seconds")
                .description("Request processing duration")
                .tag("gateway", "java-gateway")
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
        requestDuration.record(duration);

        String key = route + ":" + statusCode;
        statusCounters.computeIfAbsent(key, k ->
                Counter.builder("gateway_requests_total")
                        .description("Total number of requests processed")
                        .tag("route", route)
                        .tag("status", String.valueOf(statusCode))
                        .register(registry)
        ).increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
