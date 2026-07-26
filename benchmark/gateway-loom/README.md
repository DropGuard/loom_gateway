# gateway-loom — Loom-side benchmark app

This is the **Loom Gateway** half of the symmetric Loom-vs-SCG benchmark. Like
`gateway-scg`, it is an **external project** living under `benchmark/`, not part
of the main `loom-gateway` application. The symmetry is deliberate:

| | gateway-scg | gateway-loom |
|---|---|---|
| Framework | Spring Cloud Gateway | Quarkus + Vert.x (Netty) |
| Consumes | `spring-cloud-starter-gateway` (dep) | `loom-gateway` (dep — the main repo's gateway) |
| Wires filters via | its own filter factories | the library's `GatewayRouter` / `FilterChains` |
| Boots | Spring Boot | `@QuarkusMain` here |

## How it consumes the gateway as a library

The main `loom-gateway` project is a Quarkus application, but its filter layer
(`AuthFilter`, `CacheFilter`, `FilterChains`, `GatewayRouter`, …) is plain
`public` components with no Quarkus-magic coupling. This app depends on
`com.github.dropguard:loom-gateway` and lets ArC **discover the library's beans
automatically** — no re-implementation of the filter chain.

To make cross-jar bean discovery work:

- `application.properties` sets `quarkus.index-dependency.com-gateway.*` so Quarkus
  Jandex-indexes the `loom-gateway` jar (its `@Singleton`/`@ApplicationScoped` beans
  would otherwise be invisible to this app's Arc container).
- `BenchmarkLoomApplication` carries `@QuarkusMain(name = "benchmark-loom")`. The
  dependency also ships a `@QuarkusMain` (`GatewayApplication`); naming ours keeps
  the two distinct at build time.
- `application.properties` sets `gateway.config.path=/config/routes.yaml`, the same
  path the Docker compose mounts the shared `benchmark/config/routes.yaml` into.

`GatewayRouter` (from the library) already declares `@Route(path = "/*")` and
`@RunOnVirtualThread`, so once its bean is discovered the whole request path —
  routing, filter chain, proxying — is live with **zero routing code in this app**.

## Build & run

```bash
# from the repo root: install the library first, then build this app
mvn -q clean install -DskipTests            # builds loom-gateway into ~/.m2
cd benchmark/gateway-loom
mvn -q clean package -DskipTests            # resolves loom-gateway, builds quarkus-app
```

In the Docker benchmark, `run.py` does exactly this (`mvn install` the root, then
`package` this module) and `docker-compose.yml` builds the runtime image from the
host-built `target/quarkus-app`.

Verify locally:

```bash
java -Dgateway.config.path=/path/to/routes.yaml -jar target/quarkus-app/quarkus-run.jar
# => "Applied configuration with N routes"; GatewayRouter handles /* 
```
