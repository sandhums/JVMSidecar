# JVM sidecar (CQL / Clinical Reasoning)

Small **[Kotlin](https://kotlinlang.org/)** service on **[Ktor](https://ktor.io/)** that is the JVM **Clinical Reasoning** runtime for Atrius: ELM evaluation, PlanDefinition/ActivityDefinition **`$apply`**, and Measure **`$evaluate-measure`**. You call it over HTTP; you do **not** need to write Java or Kotlin to use it.

## Documentation

| Document | Purpose |
|----------|---------|
| **[docs/how-it-works.md](docs/how-it-works.md)** | How ELM libraries are loaded vs how FHIR servers are used, request fields, **evaluate / `$apply` / `$evaluate-measure` caching**, allowlist, troubleshooting, and where code lives. |
| **AtriusIGDraft `docs/clinical-reasoning-stack.md`** | Full stack: HFS, HTS, KR, bridge, cds-server, Atrius IG build/import, smoke test, operations (sibling repo). |

Start there for **three logical FHIR bases** (`hfsBaseUrl`, `htsBaseUrl`, optional **`libraryBaseUrl`**) and how the primary artifact can be inline **`elm`** or loaded from FHIR **`Library`** (plus **`include`** resolution).

## Three FHIR URLs

- **`hfsBaseUrl`** — clinical data (`Patient`, …) only.
- **`htsBaseUrl`** — terminology (`ValueSet/$expand`, …).
- **`libraryBaseUrl`** — knowledge repository (KR) for **primary** `Library`, CQL **`include`** libraries, PlanDefinition / ActivityDefinition / Measure. **Required** when `resolveLibraryArtifactsFromFhir` is true, and for `$apply` / `$evaluate-measure`. Includes do **not** fall back to `hfsBaseUrl`.

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

The image defaults to `SIDECAR_ENV=development` so local `docker run` works. Staging / production **must** set `SIDECAR_ENV`, `SIDECAR_ADMIN_TOKEN`, and `SIDECAR_ALLOWED_FHIR_BASES`.

Staging compose (sibling of `atrius-his` under `~/atrius/JVMsidecar`) wires this
image as service `cql-sidecar` on `127.0.0.1:8088`.

Default HTTP port is **8088** (override with env **`SIDECAR_PORT`**).

- **Health:** `GET http://localhost:8088/health`
- **Metrics:** `GET http://localhost:8088/metrics` (Prometheus); `GET /metrics.json` for JSON
- **Evaluate:** `POST http://localhost:8088/v1/evaluate/expression`
- **PlanDefinition `$apply`:** `POST http://localhost:8088/v1/plandefinition/apply`
- **ActivityDefinition `$apply`:** `POST http://localhost:8088/v1/activitydefinition/apply`
- **Measure `$evaluate-measure`:** `POST http://localhost:8088/v1/measure/evaluate`
- **Cache flush:** `POST http://localhost:8088/v1/admin/cache/libraries/clear`

See [docs/how-it-works.md](docs/how-it-works.md) for JSON schemas.

Non-dev: set `SIDECAR_ENV=staging|production`, `SIDECAR_ADMIN_TOKEN` (admin cache clear), and `SIDECAR_ALLOWED_FHIR_BASES` (comma-separated HFS/HTS/KR bases; required at startup). Evaluate / `$apply` / `$evaluate-measure` reject bases not on that list.

Optional FHIR HTTP tracing: `-Dsidecar.fhir.http.log=true` or **`SIDECAR_FHIR_HTTP_LOG=true`** (logger **`com.atrius.sidecar.fhir.http`** at INFO).

HAPI client timeouts: **`SIDECAR_FHIR_CONNECT_TIMEOUT_MS`** (default 10000), **`SIDECAR_FHIR_SOCKET_TIMEOUT_MS`** (default 30000).

## Classpath ELM helpers

Drop translated ELM XML/JSON under **`src/main/resources/elm-libraries/`** (for example **`FHIRHelpers-4.0.1.xml`**) so `include` targets resolve without sending them in every request. See [ClasspathElmLibraryProvider](src/main/kotlin/com/atrius/sidecar/cql/ElmLibrarySources.kt).
