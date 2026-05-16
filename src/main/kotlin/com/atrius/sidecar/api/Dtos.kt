@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.atrius.sidecar.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val error: String, val message: String, val path: String)

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
)

@Serializable
data class EvaluateExpressionResponse(
    val expression: String,
    val resultType: String?,
    val result: JsonElement,
)
