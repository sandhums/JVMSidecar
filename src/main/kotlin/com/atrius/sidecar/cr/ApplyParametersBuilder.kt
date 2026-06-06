package com.atrius.sidecar.cr

import java.time.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.StringType

/**
 * Builds FHIR R4 [Parameters] for [org.opencds.cqf.fhir.cr.plandefinition.PlanDefinitionProcessor.apply].
 *
 * Interval-shaped JSON (e.g. `"Measurement Period"`) is encoded as `valuePeriod` with `start`/`end`.
 * When no measurement period is supplied, defaults to the current calendar year so eCQM PlanDefinitions
 * can evaluate without every caller passing CQL parameters explicitly.
 */
internal fun buildApplyParameters(parameters: Map<String, JsonElement>?): IBaseParameters? {
    val merged = mergeWithDefaultMeasurementPeriod(parameters)
    if (merged.isEmpty()) return null

    val params = Parameters()
    for ((name, element) in merged) {
        val component = params.addParameter().setName(name)
        when (element) {
            is JsonObject ->
                if (looksLikeInterval(element)) {
                    component.value = intervalToPeriod(element)
                } else {
                    throw IllegalArgumentException(
                        "Unsupported object parameter '$name'; use interval {low, high} or scalar JSON",
                    )
                }
            is JsonPrimitive -> {
                val text = element.contentOrNull
                    ?: throw IllegalArgumentException("Parameter '$name' must not be null")
                component.value = StringType(text)
            }
            else ->
                throw IllegalArgumentException("Unsupported JSON shape for parameter '$name'")
        }
    }
    return params
}

private fun mergeWithDefaultMeasurementPeriod(
    parameters: Map<String, JsonElement>?,
): Map<String, JsonElement> {
    val merged = parameters?.toMutableMap() ?: mutableMapOf()
    if (!merged.containsKey("Measurement Period")) {
        val year = LocalDate.now().year
        merged["Measurement Period"] =
            JsonObject(
                mapOf(
                    "low" to JsonPrimitive("$year-01-01T00:00:00.000+00:00"),
                    "high" to JsonPrimitive("$year-12-31T23:59:59.999+00:00"),
                    "lowClosed" to JsonPrimitive(true),
                    "highClosed" to JsonPrimitive(true),
                ),
            )
    }
    return merged
}

private fun looksLikeInterval(obj: JsonObject): Boolean =
    (obj.containsKey("low") || obj.containsKey("start")) &&
        (obj.containsKey("high") || obj.containsKey("end"))

private fun intervalToPeriod(obj: JsonObject): Period {
    val period = Period()
    obj.stringField("low")?.let { period.startElement = DateTimeType(it) }
        ?: obj.stringField("start")?.let { period.startElement = DateTimeType(it) }
    obj.stringField("high")?.let { period.endElement = DateTimeType(it) }
        ?: obj.stringField("end")?.let { period.endElement = DateTimeType(it) }
    return period
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
