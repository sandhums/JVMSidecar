---
name: JVM CR Sidecar MVP
overview: Bring the fresh Kotlin/Maven JVMsidecar project to JDK 17 baseline, add CQ Framework v4 (`org.cqframework:*-jvm`) and HAPI FHIR client dependencies from Maven Central, and implement a minimal HTTP sidecar with health plus one evaluation endpoint wired to configurable HFS (data) and HTS (terminology) base URLs—matching the archived clinical reasoning plans before layering `$evaluate-measure` / `$apply`.
todos:
  - id: jdk17-pom
    content: Align Maven/Kotlin for JDK 17 (jvmTarget 17, compiler release 17); keep Kotlin Maven plugin config
    status: completed
  - id: cq-deps
    content: Add property-pin org.cqframework:*-jvm + compatible HAPI FHIR R4 client deps; verify with mvn dependency:tree
    status: completed
  - id: ktor-scaffold
    content: Add Ktor server dependency, application entrypoint, config for ports and default HFS/HTS base URLs
    status: completed
  - id: evaluate-endpoint
    content: "Implement GET /health and POST evaluate: parse ELM, wire CqlEngine + FHIR retrieve/terminology providers from request URLs"
    status: completed
  - id: smoke-test
    content: Add minimal test or documented curl example; fix package/exec mainClass if package moves off MainKt default
    status: completed
isProject: true
---

Saved copy of the implementation plan for this repository.

# JVM CR Sidecar (JDK 17 + org.cqframework)

## Current state

- [pom.xml](../../pom.xml): Kotlin 2.3.x; CQ Framework **4.8.0** (`engine-fhir`), Ktor HTTP, JDK **17**.
- Archived context: [.cursor/plans/fhir_clinical_reasoning.plan.md](fhir_clinical_reasoning.plan.md), [.cursor/plans/cr_stack_integration_map_75046cd1.plan.md](cr_stack_integration_map_75046cd1.plan.md).

## Maven coordinates (v4)

Pin **`org.cqframework:engine-fhir`** (pulls `engine-jvm`, `cql-to-elm-jvm`, and HAPI FHIR **8.8.1** BOM-aligned artifacts). Optional additions: `elm-fhir`, `cql-to-elm-jvm` explicit—usually transitive.

## HTTP surface (MVP)

| Endpoint | Purpose |
|----------|---------|
| `GET /health` | Liveness |
| `POST /v1/evaluate/expression` | Evaluate expression; body has `elm` (JSON or XML), optional `elmFormat` (`json` / `xml` / `auto`), legacy alias `elmJson`; plus `hfsBaseUrl` + `htsBaseUrl` |

## Deferred

- `$evaluate-measure`, PlanDefinition `$apply`, auth—per parent program plans.

## Verification

- `mvn test`
- Example: `curl -s http://localhost:8088/health`
