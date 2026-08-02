# JVM sidecar (CQL / ELM evaluation)

Small **[Kotlin](https://kotlinlang.org/)** service on **[Ktor](https://ktor.io/)** that evaluates **ELM** (Expression Logical Model) expressions using the **CQ Framework** and **CQL Engine FHIR** libraries. You call it over HTTP; you do **not** need to write Java or Kotlin to use it.

## Documentation

| Document | Purpose |
|----------|---------|
| **[docs/how-it-works.md](docs/how-it-works.md)** | How ELM libraries are loaded vs how FHIR servers are used, request fields, **evaluate / `$apply` caching**, troubleshooting, and where code lives. |
| **AtriusIGDraft `docs/clinical-reasoning-stack.md`** | Full stack: HFS, HTS, KR, bridge, cds-server, Atrius IG build/import, smoke test, operations (sibling repo). |

Start there for **three logical FHIR bases** (`hfsBaseUrl`, `htsBaseUrl`, optional **`libraryBaseUrl`**) and how the primary artifact can be inline **`elm`** or loaded from FHIR **`Library`** (plus **`include`** resolution).

## Three FHIR URLs

- **`hfsBaseUrl`** — clinical data (`Patient`, …) only.
- **`htsBaseUrl`** — terminology (`ValueSet/$expand`, …).
- **`libraryBaseUrl`** — knowledge repository (KR) for **primary** `Library` and all CQL **`include`** libraries. **Required** when `resolveLibraryArtifactsFromFhir` is true, and for `$apply`. Includes do **not** fall back to `hfsBaseUrl` (no bridge `/Library` routing needed).

## Quick start

Requirements: **JDK 17**, **Maven**.

```bash
cd JVMsidecar
mvn -q compile exec:java
```

Packaged fat jar (Docker / systemd):

```bash
mvn -q package -DskipTests
# → target/JVMsidecar-1.0-SNAPSHOT.jar (shaded, Main-Class set)
docker build -t atrius/cql-sidecar:staging .
```

Staging compose (sibling of `atrius-his` under `~/atrius/JVMsidecar`) wires this
image as service `cql-sidecar` on `127.0.0.1:8088`.

Default HTTP port is **8088** (override with env **`SIDECAR_PORT`**).

- **Health:** `GET http://localhost:8088/health`
- **Metrics:** `GET http://localhost:8088/metrics` (Prometheus); `GET /metrics.json` for JSON
- **Evaluate:** `POST http://localhost:8088/v1/evaluate/expression` with JSON body (see [docs/how-it-works.md](docs/how-it-works.md) for the schema and an example).

Non-dev: set `SIDECAR_ENV=staging|production` and `SIDECAR_ADMIN_TOKEN` (required at startup for admin cache clear).

Optional FHIR HTTP tracing: `-Dsidecar.fhir.http.log=true` or **`SIDECAR_FHIR_HTTP_LOG=true`** (logger **`com.atrius.sidecar.fhir.http`** at INFO).

## Classpath ELM helpers

Drop translated ELM XML/JSON under **`src/main/resources/elm-libraries/`** (for example **`FHIRHelpers-4.0.1.xml`**) so `include` targets resolve without sending them in every request. See [ClasspathElmLibraryProvider](src/main/kotlin/com/atrius/sidecar/cql/ElmLibrarySources.kt).
