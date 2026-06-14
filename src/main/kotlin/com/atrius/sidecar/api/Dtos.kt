@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.atrius.sidecar.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames

@Serializable
data class HealthResponse(val status: String)

/** Cumulative process metrics (`GET /metrics`). */
@Serializable
data class SidecarMetricsSnapshot(
    val evaluateTotal: Long,
    val evaluateErrors: Long,
    val evaluateAvgDurationMs: Double,
    val applyTotal: Long,
    val applyAvgDurationMs: Double,
    val libraryStackCacheHits: Long,
    val libraryStackCacheMisses: Long,
    val krLibraryFetches: Long,
)

@Serializable
data class ClearLibraryCacheResponse(
    val cleared: List<String>,
    val evaluationStacksRemoved: Int,
    val fhirLibraryResourcesRemoved: Int,
    val terminologyExpansionBucketsRemoved: Int,
)

/** JSON error body for failed requests (evaluate + generic handlers). FHIR fields set when upstream FHIR contributed to the failure. */
@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val path: String,
    val causes: List<String> = emptyList(),
    val fhirHttpVerb: String? = null,
    val fhirRequestUrl: String? = null,
    val fhirHttpStatus: Int? = null,
    val fhirResponseSnippet: String? = null,
)

@Serializable
enum class ElmFormat {
    /** ELM as JSON (CQ Framework canonical JSON encoding). */
    @SerialName("json")
    JSON,

    /** ELM as XML (e.g. CQL Studio clipboard export). */
    @SerialName("xml")
    XML,

    /** Infer from payload: leading `<` after trim → XML, otherwise JSON. */
    @SerialName("auto")
    AUTO,
}

/** Additional translated ELM libraries available for `include` resolution (e.g. FHIRHelpers). */
@Serializable
data class IncludedElmLibrary(
    @JsonNames("elmJson")
    val elm: String,
    val elmFormat: ElmFormat = ElmFormat.AUTO,
    val libraryId: String,
    val libraryVersion: String? = null,
)

/**
 * Evaluates one expression from translated **ELM** (not raw CQL).
 *
 * **Primary artifact:** If [elm] is non-blank, it is always the primary library (**inline wins**). If [elm] is null or
 * blank, the primary must be loaded from FHIR **`Library.content`** (requires [resolveLibraryArtifactsFromFhir] **true**).
 *
 * **Includes:** [includedLibraries] and classpath `/elm-libraries/`; remaining **`include`** targets may use FHIR
 * **`Library`** when [resolveLibraryArtifactsFromFhir] is true — see [libraryBaseUrl] and **`docs/how-it-works.md`**.
 *
 * **Clinical / terminology:** [hfsBaseUrl] and [htsBaseUrl] drive Patient retrieves and `$expand`, etc.
 */
@Serializable
data class EvaluateExpressionRequest(
    /**
     * Primary ELM document (JSON or XML). Alias **`elmJson`**. **Null or blank** loads the primary from FHIR
     * **`Library`** using [libraryId] / [libraryVersion] when [resolveLibraryArtifactsFromFhir] is **true**.
     */
    @JsonNames("elmJson")
    val elm: String? = null,
    val elmFormat: ElmFormat = ElmFormat.AUTO,
    val libraryId: String,
    val libraryVersion: String? = null,
    val expression: String,
    /**
     * Clinical FHIR HTTP **base URL** passed to HAPI’s generic client (any FHIR-compliant server works —
     * e.g. HAPI on `http://host/fhir`, or Helios with resources at root like `http://host:8088` so that
     * `GET {hfsBaseUrl}/Patient/{id}` matches your deployment).
     */
    val hfsBaseUrl: String,
    /**
     * Terminology FHIR base for ValueSet `$expand` and related calls (same URL rules as [hfsBaseUrl] —
     * whatever prefix makes `{htsBaseUrl}/ValueSet/...` correct for your HTS).
     */
    val htsBaseUrl: String,
    /**
     * Knowledge-artifact FHIR base for **`Library`** reads when resolving missing includes (`GET Library/{id}` or
     * `Library?name=…&version=…`). **Null or blank** means use [hfsBaseUrl] (common until you split artifact FHIR).
     */
    val libraryBaseUrl: String? = null,
    /**
     * When true, fetch FHIR **`Library`** ELM for missing **`include`** targets and (when [elm] is null or blank) for the **primary** library.
     * Precedence: inline body → classpath → FHIR [libraryBaseUrl]. Set false to forbid outbound `Library` fetches (requires non-blank [elm]).
     */
    val resolveLibraryArtifactsFromFhir: Boolean = true,
    /**
     * Extra ELM payloads for [org.hl7.elm.r1.IncludeDef] targets. Classpath fallback: `/elm-libraries/{id}-{version}.xml|json`.
     */
    val includedLibraries: List<IncludedElmLibrary> = emptyList(),
    /** Logical id of the Patient in scope (`Patient/{id}`); passed as the engine's Patient context **string** for FHIR REST retrieves (not a full resource). */
    val patientId: String? = null,
    val parameters: Map<String, JsonElement>? = null,
    val evaluationDateTime: String? = null,
    /** CDS Hooks prefetch map (key → FHIR resource or searchset Bundle JSON). */
    val prefetch: Map<String, JsonElement>? = null,
    /**
     * SMART bearer credentials for clinical FHIR ([hfsBaseUrl] only). Forwarded from CDS Hooks
     * `fhirAuthorization` by cds-server. Omitted for server-trust paths (e.g. cr-fhir-bridge).
     */
    val fhirAuthorization: FhirAuthorizationCredentials? = null,
)

/** OAuth 2.0 bearer token metadata from CDS Hooks `fhirAuthorization`. */
@Serializable
data class FhirAuthorizationCredentials(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long? = null,
    val scope: String? = null,
    val subject: String? = null,
    val patient: String? = null,
)

@Serializable
data class EvaluateExpressionResponse(
    val expression: String,
    val resultType: String?,
    val result: JsonElement,
)

/**
 * Invokes **`PlanDefinition/$apply`** on the sidecar (CQF Clinical Reasoning).
 *
 * Mirrors FHIR operation inputs: [patientId] → **subject**; optional encounter / practitioner /
 * organization references; CodeableConcept context ([userType], [userLanguage], [userTaskContext],
 * [setting], [settingContext]) exposed to CQL as `%param` context variables per the CR IG.
 *
 * Provide [planDefinitionId] (KR logical id) or [planDefinitionUrl] (canonical). Clinical / terminology /
 * content bases match [EvaluateExpressionRequest].
 */
@Serializable
data class ApplyPlanDefinitionRequest(
    val planDefinitionId: String? = null,
    val planDefinitionUrl: String? = null,
    /** FHIR `$apply` **subject** (Patient logical id or reference). */
    val patientId: String,
    /** FHIR `$apply` **encounter** (Encounter logical id or reference). */
    val encounterId: String? = null,
    /**
     * FHIR `$apply` **practitioner** — CDS Hooks `context.userId` when Practitioner / PractitionerRole.
     * Full `ResourceType/id` references are passed through; bare ids default to `Practitioner/{id}`.
     */
    val practitionerId: String? = null,
    /** FHIR `$apply` **organization** (Organization logical id or reference). */
    val organizationId: String? = null,
    /** FHIR `$apply` **userType** (CodeableConcept JSON). */
    val userType: JsonElement? = null,
    /** FHIR `$apply` **userLanguage** (CodeableConcept JSON). */
    val userLanguage: JsonElement? = null,
    /** FHIR `$apply` **userTaskContext** (CodeableConcept JSON). */
    val userTaskContext: JsonElement? = null,
    /** FHIR `$apply` **setting** (CodeableConcept JSON). */
    val setting: JsonElement? = null,
    /** FHIR `$apply` **settingContext** (CodeableConcept JSON). */
    val settingContext: JsonElement? = null,
    val hfsBaseUrl: String,
    val htsBaseUrl: String,
    val libraryBaseUrl: String? = null,
    /** When true, clinical data comes from server REST only (no prefetch overlay). */
    val useServerData: Boolean = false,
    /** CDS Hooks prefetch map (key → FHIR resource JSON). Ignored when [useServerData] is true. */
    val prefetch: Map<String, JsonElement>? = null,
    /** CQL library parameters (→ FHIR `$apply` **parameters**). */
    val parameters: Map<String, JsonElement>? = null,
    /** SMART bearer credentials for clinical FHIR ([hfsBaseUrl] only). */
    val fhirAuthorization: FhirAuthorizationCredentials? = null,
)

@Serializable
data class ApplyPlanDefinitionResponse(
    val planDefinitionId: String? = null,
    /** Primary FHIR `$apply` result: CarePlan with activity → RequestGroup. */
    val carePlan: JsonElement,
    /** Extracted RequestGroup for CDS card mapping (same resource referenced by [carePlan].activity). */
    val requestGroup: JsonElement,
)

/**
 * Invokes **`ActivityDefinition/$apply`** on the sidecar (CQF Clinical Reasoning).
 *
 * Returns a draft request resource (`ServiceRequest`, `MedicationRequest`, `Task`, …) per
 * [ActivityDefinition.kind], with structural mapping, participant/location resolution,
 * [dynamicValue] evaluation, and optional StructureMap [transform] applied by CQF.
 */
@Serializable
data class ApplyActivityDefinitionRequest(
    val activityDefinitionId: String? = null,
    val activityDefinitionUrl: String? = null,
    /** FHIR `$apply` **subject** (Patient logical id or reference). */
    val patientId: String,
    val encounterId: String? = null,
    val practitionerId: String? = null,
    val organizationId: String? = null,
    val userType: JsonElement? = null,
    val userLanguage: JsonElement? = null,
    val userTaskContext: JsonElement? = null,
    val setting: JsonElement? = null,
    val settingContext: JsonElement? = null,
    val hfsBaseUrl: String,
    val htsBaseUrl: String,
    val libraryBaseUrl: String? = null,
    val useServerData: Boolean = false,
    val prefetch: Map<String, JsonElement>? = null,
    val parameters: Map<String, JsonElement>? = null,
    val fhirAuthorization: FhirAuthorizationCredentials? = null,
)

@Serializable
data class ApplyActivityDefinitionResponse(
    val activityDefinitionId: String? = null,
    /** FHIR `$apply` **return** — transient draft request/event resource (not persisted). */
    val resource: JsonElement,
)
