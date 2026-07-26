package com.benchmark.loom;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

import org.jboss.logging.Logger;

/**
 * Benchmark entry point for the Loom side. The actual request handling, filter
 * chain, and routing all live in the {@code com.github.dropguard.loom} library
 * (java-gateway), consumed as a Maven dependency — this class only boots Quarkus
 * and lets the library's {@code GatewayRouter} (a {@code @ApplicationScoped} with
 * a {@code @Route(path = "/*")}) take over. Symmetric with gateway-scg, which
 * boots Spring Boot and lets its own filter factories handle traffic.
 */
@QuarkusMain(name = "benchmark-loom")
public class BenchmarkLoomApplication implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(BenchmarkLoomApplication.class);

    @Override
    public int run(String... args) {
        LOG.info("Loom Gateway (benchmark) started - consuming java-gateway as a library");
        Quarkus.waitForExit();
        return 0;
    }

    public static void main(String[] args) {
        Quarkus.run(BenchmarkLoomApplication.class, args);
    }
}
