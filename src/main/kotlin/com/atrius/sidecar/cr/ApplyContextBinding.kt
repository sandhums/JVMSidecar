package com.atrius.sidecar.cr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.hl7.fhir.instance.model.api.IBaseDatatype
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding

/**
 * Binds FHIR **`PlanDefinition/$apply`** operation context inputs from sidecar JSON.
 *
 * Reference parameters accept logical ids or `ResourceType/id` references.
 * CodeableConcept parameters accept FHIR JSON objects (or `{ "text": "..." }` shorthand).
 */
internal fun trimReference(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Normalizes a `$apply` reference parameter.
 *
 * When [defaultResourceType] is set and [raw] has no slash, returns `ResourceType/id`.
 * Full references (`PractitionerRole/abc`) are passed through unchanged.
 */
internal fun normalizeApplyReference(raw: String?, defaultResourceType: String): String? {
    val trimmed = trimReference(raw) ?: return null
    if (trimmed.contains("/")) return trimmed
    return "$defaultResourceType/$trimmed"
}

internal fun parseCodeableConcept(element: JsonElement?): CodeableConcept? {
    if (element == null || element !is JsonObject) return null
    val cc = CodeableConcept()
    (element["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { cc.text = it }
    (element["coding"] as? JsonArray)?.forEach { codingEl ->
        if (codingEl !is JsonObject) return@forEach
        val coding = Coding()
        (codingEl["system"] as? JsonPrimitive)?.contentOrNull?.let { coding.system = it }
        (codingEl["code"] as? JsonPrimitive)?.contentOrNull?.let { coding.code = it }
        (codingEl["display"] as? JsonPrimitive)?.contentOrNull?.let { coding.display = it }
        if (coding.system != null || coding.code != null || coding.display != null) {
            cc.addCoding(coding)
        }
    }
    return if (cc.hasText() || cc.hasCoding()) cc else null
}

internal fun parseCodeableConceptElement(element: JsonElement?): IBaseDatatype? =
    parseCodeableConcept(element)

internal fun looksLikeCodeableConcept(obj: JsonObject): Boolean =
    obj.containsKey("coding") || obj.containsKey("text")
