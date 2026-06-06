# JVM sidecar (CQL / ELM evaluation)

Small **[Kotlin](https://kotlinlang.org/)** service on **[Ktor](https://ktor.io/)** that evaluates **ELM** (Expression Logical Model) expressions using the **CQ Framework** and **CQL Engine FHIR** libraries. You call it over HTTP; you do **not** need to write Java or Kotlin to use it.

## Documentation

| Document | Purpose |
|----------|---------|
| **[docs/how-it-works.md](docs/how-it-works.md)** | How ELM libraries are loaded vs how FHIR servers are used, request fields, troubleshooting, and where code lives if you want to read it later. |
| **AtriusIGDraft `docs/clinical-reasoning-stack.md`** | Full stack: HFS, HTS, KR, bridge, cds-server, Atrius IG build/import, smoke test, operations (sibling repo). |

Start there for **three logical FHIR bases** (`hfsBaseUrl`, `htsBaseUrl`, optional **`libraryBaseUrl`**) and how the primary artifact can be inline **`elm`** or loaded from FHIR **`Library`** (plus **`include`** resolution).

## Three FHIR URLs

- **`hfsBaseUrl`** — clinical data (`Patient`, …).
- **`htsBaseUrl`** — terminology (`ValueSet/$expand`, …).
- **`libraryBaseUrl`** — optional; knowledge **`Library`** fetch (primary when **`elm`** is omitted, or missing **`include`**s after classpath). Omit it to default to **`hfsBaseUrl`** until you split artifact FHIR.

## Quick start

Requirements: **JDK 17**, **Maven**.

```bash
cd JVMsidecar
mvn -q compile exec:java
```

Default HTTP port is **8088** (override with env **`SIDECAR_PORT`**).

- **Health:** `GET http://localhost:8088/health`
- **Evaluate:** `POST http://localhost:8088/v1/evaluate/expression` with JSON body (see [docs/how-it-works.md](docs/how-it-works.md) for the schema and an example).

Optional FHIR HTTP tracing: `-Dsidecar.fhir.http.log=true` or **`SIDECAR_FHIR_HTTP_LOG=true`** (logger **`com.atrius.sidecar.fhir.http`** at INFO).

## Classpath ELM helpers

Drop translated ELM XML/JSON under **`src/main/resources/elm-libraries/`** (for example **`FHIRHelpers-4.0.1.xml`**) so `include` targets resolve without sending them in every request. See [ClasspathElmLibraryProvider](src/main/kotlin/com/atrius/sidecar/cql/ElmLibrarySources.kt).
