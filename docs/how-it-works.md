# How the JVM sidecar works

This guide is for anyone using the HTTP API. You do **not** need to know Kotlin or Java to operate the service; the sections at the end only map concepts to source files if you want to explore the codebase.

---

## 1. What “evaluation” means here

When you **POST** `/v1/evaluate/expression`, the sidecar:

1. **Loads ELM libraries** from these sources (details below):
   - **Primary:** non-blank **`elm`** in the JSON body (**always wins** when present), **or** FHIR **`Library.content`** when **`elm`** is omitted and **`resolveLibraryArtifactsFromFhir`** is **true** (**`libraryId`** / **`libraryVersion`** identify the resource on **`libraryBaseUrl`** or **`hfsBaseUrl`**).
   - **`include` dependencies:** **`includedLibraries`** → classpath **`/elm-libraries/`** → FHIR **`Library`** (same flag and base URL).
2. **Compiles / resolves** those libraries with CQ Framework’s **`LibraryManager`** (this turns ELM into something the engine can run).
3. **Connects to your FHIR servers** for **runtime data**:
   - **Clinical base URL (`hfsBaseUrl`)** — e.g. read `Patient/{id}`, search `Condition`, etc., when the expression asks for FHIR data.
   - **Terminology base URL (`htsBaseUrl`)** — e.g. **`ValueSet/$expand`** when codes need expansion.

The **result** of the expression (string, number, list, etc.) is returned as JSON. FHIR **Patient** resources used during evaluation are **not** the same thing as **CQL Library** artifacts.

---

## 2. Two different things called “library”

| Concept | What it is | Where this sidecar gets it |
|--------|------------|-----------------------------|
| **CQL / ELM library** | A **knowledge artifact**: translated logic (ELM XML or JSON), possibly with `include` references to other libraries (e.g. **FHIRHelpers**). | Inline **`elm`** (optional when primary comes from FHIR), **`includedLibraries`**, classpath **`elm-libraries`**, and FHIR **`Library`** on **`libraryBaseUrl`**. See precedence in §1 and §3. |
| **FHIR `Library` resource** | A **FHIR REST resource** that may attach **ELM** (`application/elm+xml`, `application/elm+json`) or **`text/cql`** in **`content[]`**. | When **`resolveLibraryArtifactsFromFhir`** is **true**, the sidecar can load the **primary** from FHIR when **`elm`** is omitted, and load **`include`** targets after classpath misses, via **`GET Library/{id}`** or **`Library?name=…&version=…`** on **`libraryBaseUrl`** (defaulting to **`hfsBaseUrl`**). **`text/cql`** is **not** compiled — store ELM attachments or send ELM inline/classpath. |

**Resolution order** for **`include`**: inline **`includedLibraries`** → classpath → FHIR **`Library`** (if enabled).

---

## 3. FHIR `Library` fetch: primary (optional) and includes

### Primary library

- **Inline wins:** If **`elm`** is non-blank, it is always the primary ELM document (FHIR is **not** used for the primary).
- **FHIR primary:** If **`elm`** is omitted or blank, set **`resolveLibraryArtifactsFromFhir`** to **true** and supply **`libraryId`** (and **`libraryVersion`** when you need an exact match). The sidecar loads **`Library`** the same way as for includes (`GET Library/{id}`, then search by **`name`/`version`**), decodes **`application/elm+xml`** if present, otherwise **`application/elm+json`**.

If **`elm`** is blank and **`resolveLibraryArtifactsFromFhir`** is **false**, evaluation fails (there is no primary artifact).

### Includes

For **`include`** dependencies missing from **`includedLibraries`** and classpath:

1. **`GET Library/{id}`** using the ELM library **logical id** (e.g. `FHIRHelpers` when the include path is `FHIRHelpers`).
2. If that fails, **`GET Library?name={id}`**, adding **`&version={version}`** when the include specifies a version **and** your server supports search on **`version`**.

The loader chooses **`Library.content`** attachments with **`application/elm+xml`** / **`application/elm+json`** (with small MIME fallbacks). **`text/cql`** is ignored — translate to ELM before storage or send ELM in the POST/classpath.

**Multi-tenant SaaS:** use **`libraryBaseUrl`** as the **knowledge-artifact** FHIR API root; leave it **null** to default to **`hfsBaseUrl`** until you split artifact FHIR from clinical FHIR. Set **`resolveLibraryArtifactsFromFhir`** to **false** if outbound **`Library`** reads must be disabled for a tenant (you must then supply non-blank **`elm`** for the primary).

---

## 4. HTTP API reference

### `GET /health`

Returns a small JSON object confirming the process is up.

### `POST /v1/evaluate/expression`

Content-Type: **`application/json`**.

Main fields (see also **`EvaluateExpressionRequest`** in source: [`Dtos.kt`](../src/main/kotlin/com/atrius/sidecar/api/Dtos.kt)):

| Field | Required | Meaning |
|-------|----------|---------|
| **`elm`** | Conditional | Primary ELM **string** when provided (alias **`elmJson`**). **Non-blank always wins** over FHIR. **Omit or null** to load the primary from FHIR **`Library`** (requires **`resolveLibraryArtifactsFromFhir`** **true**). |
| **`elmFormat`** | No | `auto` (default), `xml`, or `json` — used when **`elm`** is inline; ignored for FHIR primary (format comes from the attachment). |
| **`libraryId`** | Yes | Must match the ELM library’s logical **`id`** (and is used to **`GET Library/{id}`** when loading from FHIR). |
| **`libraryVersion`** | No | Optional; if present and the ELM carries a version, they must agree. |
| **`expression`** | Yes | Name of the expression definition to evaluate (e.g. **`PatientName`**). |
| **`hfsBaseUrl`** | Yes | Base URL for **clinical** FHIR HTTP calls (whatever prefix makes **`GET …/Patient/{id}`** correct for your deployment). |
| **`htsBaseUrl`** | Yes | Base URL for **terminology** FHIR HTTP calls (e.g. **`ValueSet/$expand`**). |
| **`libraryBaseUrl`** | No | Base URL for **`Library`** reads when resolving missing includes. **Omit or blank** → same as **`hfsBaseUrl`**. Use a dedicated artifact FHIR when splitting knowledge from clinical data. |
| **`resolveLibraryArtifactsFromFhir`** | No | Default **true**. If **false**, no outbound **`Library`** fetches (**requires non-blank `elm`** for the primary). |
| **`includedLibraries`** | No | List of extra ELM payloads for **`include`** targets (each entry has **`elm`**, **`libraryId`**, optional **`libraryVersion`**, **`elmFormat`**). |
| **`patientId`** | No | If set, establishes **Patient context** as the **id string** (not the full resource). The engine passes this to FHIR retrieve logic for patient-scoped queries. |
| **`parameters`** | No | Extra engine parameters (JSON values mapped into Java/Kotlin types). |
| **`evaluationDateTime`** | No | ISO-8601 instant; defaults to “now”. |

**Classpath / FHIR fallback for includes:** if an included library is **not** in `includedLibraries`, the loader looks for **`/elm-libraries/{libraryId}-{version}.xml|json`** then **`/elm-libraries/{libraryId}.xml|json`** on the classpath—see [`ElmLibrarySources.kt`](../src/main/kotlin/com/atrius/sidecar/cql/ElmLibrarySources.kt). If still missing and **`resolveLibraryArtifactsFromFhir`** is **true**, it tries FHIR **`Library`** on **`libraryBaseUrl`** (see [`FhirLibraryElmLoader.kt`](../src/main/kotlin/com/atrius/sidecar/cql/FhirLibraryElmLoader.kt), [`FhirElmLibrarySourceProvider.kt`](../src/main/kotlin/com/atrius/sidecar/cql/FhirElmLibrarySourceProvider.kt)).

---

## 5. FHIR client behavior (clinical + terminology + optional library)

- The sidecar uses **HAPI FHIR** **`IGenericClient`** only as an **HTTP client** for FHIR resources and operations needed during evaluation.
- **`encoding`** is set to **JSON** so requests prefer **`application/fhir+json`**, which avoids servers that mishandle XML for some interactions.
- On first use, HAPI may call **`GET /metadata`** on each distinct base URL (**server validation**): **`hfsBaseUrl`**, **`htsBaseUrl`**, and (when **`resolveLibraryArtifactsFromFhir`** is **true**) the effective **library** base (**`libraryBaseUrl`** or **`hfsBaseUrl`**). Each must serve a valid **CapabilityStatement** when that client is exercised.

Detailed KDoc: [`SidecarEvaluator.kt`](../src/main/kotlin/com/atrius/sidecar/cql/SidecarEvaluator.kt).

---

## 6. Debugging

- **FHIR HTTP trace:** `-Dsidecar.fhir.http.log=true` or **`SIDECAR_FHIR_HTTP_LOG=true`** — logger **`com.atrius.sidecar.fhir.http`** (INFO).
- **Rich errors:** evaluation failures attempt to include nested **`OperationOutcome`** text when the underlying exception is from HAPI — see **`EvaluationThrowableFormatting`**.

---

## 7. Running from Maven / IDE

- **Main class:** `com.atrius.sidecar.MainKt`
- **Port:** **`SIDECAR_PORT`** (default **8088**)

Example:

```bash
mvn -q compile exec:java
```

---

## 8. Source map (optional reading)

If you want to browse Kotlin without changing anything:

| Topic | File |
|-------|------|
| HTTP routes | [`Routing.kt`](../src/main/kotlin/com/atrius/sidecar/server/routes/Routing.kt) |
| Request/response JSON models | [`Dtos.kt`](../src/main/kotlin/com/atrius/sidecar/api/Dtos.kt) |
| End-to-end evaluation | [`SidecarEvaluator.kt`](../src/main/kotlin/com/atrius/sidecar/cql/SidecarEvaluator.kt) |
| ELM from POST + classpath + FHIR Library | [`ElmLibrarySources.kt`](../src/main/kotlin/com/atrius/sidecar/cql/ElmLibrarySources.kt), [`FhirLibraryElmLoader.kt`](../src/main/kotlin/com/atrius/sidecar/cql/FhirLibraryElmLoader.kt), [`FhirElmLibrarySourceProvider.kt`](../src/main/kotlin/com/atrius/sidecar/cql/FhirElmLibrarySourceProvider.kt) |
| ELM parsing / hydration | [`ElmLibraryParsing.kt`](../src/main/kotlin/com/atrius/sidecar/cql/ElmLibraryParsing.kt), [`ElmLibraryHydration.kt`](../src/main/kotlin/com/atrius/sidecar/cql/ElmLibraryHydration.kt) |

---

## 9. Real-world pattern: storing CQL + ELM on a FHIR `Library` (Clinical Reasoning context)

What you described—**author in CQL Studio**, **translate to ELM XML**, **persist both** as separate **`Library.content`** attachments (`text/cql`, `application/elm+xml`)—matches how **FHIR Clinical Reasoning** and related IGs treat knowledge artifacts:

- **`Library`** is the standard container for **machine-executable logic** and metadata (**`name`**, **`version`**, **`url`**, **`status`**, **`type`**, narrative **`description`**).
- **`content[]`** holds **one or more representations** of the same logical library: typically **source CQL** for humans and tooling, plus **compiled ELM** (XML or JSON) for **runtimes that evaluate ELM directly** without compiling CQL on every request.

**Why keep both attachments**

| Attachment | Typical role |
|------------|----------------|
| **`text/cql`** | Source of truth for authors; diff/review; **re-translate** when the translator or FHIR model version changes; regulatory or audit trails. |
| **`application/elm+xml`** or **`application/elm+json`** | **Fast, deterministic execution**: the CQ/Java engine path used by this sidecar already consumes ELM and resolves **`include`** against other ELM payloads (or classpath). Avoids shipping a CQL compiler and matching options at runtime unless you choose to. |

**How production systems usually wire it (conceptually)**

1. **Authoring pipeline** — Edit CQL → translate with pinned translator options → validate (optionally **FHIR `$validate`** on the **`Library`** resource, terminology checks, etc.) → **`PUT`/`POST` `Library`** with updated **`content`** and **`meta.versionId`** bump.
2. **Execution pipeline** — Something resolves **which `Library` version** applies (canonical **`url` + `version`**, Measure/`PlanDefinition` reference, your own registry). That component **reads `Library`**, picks the **`content`** entry with the right **`contentType`**, **base64-decodes `data`**, and either:
   - **evaluates inside a FHIR-aware service** (e.g. server-side Clinical Reasoning **`$evaluate`**-style flows where the server loads `Library` and drives the engine), or
   - **forwards ELM bytes** to an evaluator **over the wire**, or **omit `elm`** on **`POST /v1/evaluate/expression`** so the sidecar loads the primary from **`libraryBaseUrl`** when **`resolveLibraryArtifactsFromFhir`** is true — still pass **`includedLibraries`** / classpath / FHIR for **`include`** targets as needed — with **`hfsBaseUrl`/`htsBaseUrl`** for clinical + terminology data.

**Includes (e.g. FHIRHelpers)** — The ELM already references **`FHIRHelpers` `4.0.1`**. At execution time you still need **those bytes** available (FHIR **`Library`** search by name/url for the helper, classpath in the sidecar, or **`includedLibraries`** from Rust). That is **artifact resolution**, separate from **Patient data** on HFS.

**Where the JVM sidecar sits** — It evaluates **ELM** against clinical + terminology FHIR URLs. It can load the **primary** from **`Library.content`** when **`elm`** is omitted (with **`resolveLibraryArtifactsFromFhir`**), resolve **`include`** libraries from FHIR after classpath misses, and still allows inline **`elm`** for tooling or overrides.

---

## Summary

- **`hfsBaseUrl`** → clinical data; **`htsBaseUrl`** → terminology; **`libraryBaseUrl`** (optional) → knowledge **`Library`** reads for FHIR-backed artifacts.
- **Primary ELM:** non-blank **`elm`** in JSON **or** FHIR **`Library`** when **`elm`** is omitted (requires **`resolveLibraryArtifactsFromFhir`**).
- **`include`** ELM: request **`includedLibraries`**, classpath **`elm-libraries`**, then FHIR **`Library`** (**ELM MIME types only**).

Storing **CQL + ELM** on a FHIR **`Library`** matches common CR practice; inline **`elm`** remains supported for tests and escape hatches.
