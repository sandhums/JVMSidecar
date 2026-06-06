package com.atrius.sidecar.cql

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.cqframework.cql.shared.BigDecimal
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.ParameterDef
import org.hl7.elm.r1.TypeSpecifier
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.runtime.Concept
import org.opencds.cqf.cql.engine.runtime.Date
import org.opencds.cqf.cql.engine.runtime.DateTime
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Quantity
import org.opencds.cqf.cql.engine.runtime.Time
import org.opencds.cqf.cql.engine.util.zoneOffsetOfHoursMinutes

private val UTC_OFFSET = zoneOffsetOfHoursMinutes(0, 0)

/**
 * Converts JSON request parameters into CQ engine runtime values.
 *
 * Uses [Library.parameters] type specifiers when available (e.g. `Interval<DateTime>` for
 * `"Measurement Period"`). Supports both CQL-style `{low, high, lowClosed, highClosed}` and
 * legacy `{start, end}` shapes.
 */
internal fun convertEngineParameters(
    library: Library?,
    parameters: Map<String, JsonElement>,
): Map<String, Any?> =
    parameters.mapValues { (name, value) ->
        convertEngineParameter(name, value, parameterDefFor(library, name))
    }

private fun parameterDefFor(library: Library?, name: String): ParameterDef? =
    library?.parameters?.def?.firstOrNull { it.name == name }

internal fun convertEngineParameter(
    name: String,
    element: JsonElement,
    parameterDef: ParameterDef? = null,
): Any? {
    if (element is JsonNull) return null

    val explicitType = element.takeIf { it is JsonObject }?.jsonObject?.get("type")?.let { typeElement ->
        (typeElement as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
    val payload =
        when (element) {
            is JsonObject -> {
                element["value"]?.takeIf { it !is JsonNull }?.let { nested ->
                    if (nested is JsonObject) nested else element
                } ?: element.withoutKey("type")
            }
            else -> element
        }

    val typeHint = explicitType ?: typeHintFromParameterDef(parameterDef)
    return convertTypedJsonElement(payload, typeHint, name)
}

private fun JsonObject.withoutKey(key: String): JsonObject {
    val filtered = entries.filter { it.key != key }.associate { it.key to it.value }
    return JsonObject(filtered)
}

private fun typeHintFromParameterDef(parameterDef: ParameterDef?): String? {
    val spec = parameterDef?.parameterTypeSpecifier ?: parameterDef?.resultTypeSpecifier ?: return null
    return typeHintFromSpecifier(spec)
}

private fun typeHintFromSpecifier(spec: TypeSpecifier): String? =
    when (spec) {
        is NamedTypeSpecifier -> bracedLocalName(spec.name?.localPart) ?: spec.name?.localPart
        is IntervalTypeSpecifier -> {
            val point = spec.pointType?.let { typeHintFromSpecifier(it) } ?: "DateTime"
            "Interval<$point>"
        }
        else -> null
    }

private fun bracedLocalName(text: String?): String? {
    if (text.isNullOrBlank()) return null
    if (!text.startsWith("{") || !text.contains("}")) return text
    val end = text.indexOf('}')
    if (end <= 1) return text
    return text.substring(end + 1).takeIf { it.isNotBlank() }
}

@Suppress("ReturnCount")
private fun convertTypedJsonElement(
    element: JsonElement,
    typeHint: String?,
    parameterName: String,
): Any? {
    if (element is JsonNull) return null

    val normalizedHint = typeHint?.trim()?.takeIf { it.isNotBlank() }
    if (normalizedHint != null) {
        convertByTypeHint(element, normalizedHint)?.let { return it }
    }

    if (element is JsonObject && looksLikeInterval(element)) {
        val pointType = intervalPointType(normalizedHint)
        return convertIntervalObject(element, pointType)
    }

    return convertScalarJsonElement(element, normalizedHint)
        ?: throw IllegalArgumentException(
            "Unable to convert parameter '$parameterName' to a CQL engine value" +
                (normalizedHint?.let { " (expected $it)" } ?: ""),
        )
}

private fun intervalPointType(typeHint: String?): String {
    if (typeHint == null) return "DateTime"
    val start = typeHint.indexOf('<')
    val end = typeHint.lastIndexOf('>')
    if (start < 0 || end <= start) return "DateTime"
    return typeHint.substring(start + 1, end).trim().ifBlank { "DateTime" }
}

private fun looksLikeInterval(obj: JsonObject): Boolean =
    (obj.containsKey("low") || obj.containsKey("start")) &&
        (obj.containsKey("high") || obj.containsKey("end"))

@Suppress("ReturnCount")
private fun convertByTypeHint(element: JsonElement, typeHint: String): Any? {
    val baseType = typeHint.substringBefore('<').trim()
    val subType = typeHint.substringAfter('<', "").substringBefore('>').trim().ifBlank { null }

    return when (baseType.lowercase()) {
        "boolean" -> element.asBoolean()
        "string" -> element.asString()
        "integer" -> element.asInt()
        "decimal" -> element.asDecimal()
        "date" -> element.asString()?.let { Date(it) }
        "datetime" -> element.asString()?.let { parseDateTime(it) }
        "time" -> element.asString()?.let { parseTime(it) }
        "quantity" -> (element as? JsonObject)?.let { parseQuantity(it) }
        "code" -> (element as? JsonObject)?.let { parseCode(it) }
        "concept" -> (element as? JsonObject)?.let { parseConcept(it) }
        "interval" ->
            when (element) {
                is JsonObject -> convertIntervalObject(element, subType ?: "DateTime")
                else -> null
            }
        else -> null
    }
}

private fun convertIntervalObject(obj: JsonObject, pointType: String): Interval {
    val lowKey = if (obj.containsKey("low")) "low" else "start"
    val highKey = if (obj.containsKey("high")) "high" else "end"
    val lowElement =
        obj[lowKey]
            ?: throw IllegalArgumentException("Interval parameter must include '$lowKey'")
    val highElement =
        obj[highKey]
            ?: throw IllegalArgumentException("Interval parameter must include '$highKey'")
    val lowClosed = obj.booleanOrDefault("lowClosed", default = true)
    val highClosed = obj.booleanOrDefault("highClosed", default = true)
    val low = convertIntervalBoundary(lowElement, pointType)
    val high = convertIntervalBoundary(highElement, pointType)
    return Interval(low, lowClosed, high, highClosed)
}

private fun convertIntervalBoundary(element: JsonElement, pointType: String): Any? {
    if (element is JsonNull) return null
    return when (pointType.lowercase()) {
        "integer" -> element.asInt()
        "decimal" -> element.asDecimal()
        "date" -> element.asString()?.let { Date(it) }
        "datetime" -> element.asString()?.let { parseDateTime(it) }
        "time" -> element.asString()?.let { parseTime(it) }
        "quantity" -> (element as? JsonObject)?.let { parseQuantity(it) }
        else -> element.asString()?.let { parseDateTime(it) }
    }
}

private fun parseDateTime(raw: String): DateTime {
    val text = raw.removePrefix("@").trim()
    return DateTime(text, UTC_OFFSET)
}

private fun parseTime(raw: String): Time {
    val text =
        when {
            raw.startsWith("@T") -> raw.removePrefix("@")
            raw.startsWith("T") -> raw
            else -> "T$raw"
        }
    return Time(text)
}

private fun parseQuantity(obj: JsonObject): Quantity {
    val value =
        obj["value"]?.asDecimal()
            ?: throw IllegalArgumentException("Quantity parameter must include numeric 'value'")
    val unit = obj["unit"]?.asString() ?: "1"
    return Quantity().withValue(value).withUnit(unit)
}

private fun parseCode(obj: JsonObject): Code {
    val code =
        obj["code"]?.asString()
            ?: throw IllegalArgumentException("Code parameter must include 'code'")
    return Code().apply {
        withCode(code)
        obj["system"]?.asString()?.let { withSystem(it) }
        obj["display"]?.asString()?.let { withDisplay(it) }
        obj["version"]?.asString()?.let { withVersion(it) }
    }
}

private fun parseConcept(obj: JsonObject): Concept {
    val codes =
        obj["codes"]?.let { codesElement ->
            when (codesElement) {
                is JsonArray ->
                    codesElement.mapNotNull { item ->
                        (item as? JsonObject)?.let { parseCode(it) }
                    }
                is JsonObject -> listOfNotNull(parseCode(codesElement))
                else -> emptyList()
            }
        } ?: emptyList()
    return Concept().apply {
        withCodes(codes)
        obj["display"]?.asString()?.let { withDisplay(it) }
    }
}

private fun convertScalarJsonElement(element: JsonElement, typeHint: String?): Any? =
    when (element) {
        is JsonPrimitive -> {
            element.booleanOrNull?.let { return it }
            element.longOrNull?.let { return it }
            element.doubleOrNull?.let { return BigDecimal(it.toString()) }
            element.contentOrNull?.let { text ->
                if (typeHint.equals("DateTime", ignoreCase = true) || text.startsWith("@")) {
                    return parseDateTime(text)
                }
                if (typeHint.equals("Date", ignoreCase = true)) {
                    return Date(text)
                }
                if (typeHint.equals("Time", ignoreCase = true) || text.startsWith("T")) {
                    return parseTime(text)
                }
                return text
            }
        }
        is JsonArray -> element.map { convertScalarJsonElement(it, typeHint) }
        else -> null
    }

private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
    this[key]?.let { el ->
        (el as? JsonPrimitive)?.booleanOrNull
    } ?: default

private fun JsonElement.asBoolean(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement.asInt(): Int? =
    (this as? JsonPrimitive)?.longOrNull?.toInt()

private fun JsonElement.asDecimal(): BigDecimal? =
    when (this) {
        is JsonPrimitive ->
            longOrNull?.let { BigDecimal(it.toString()) }
                ?: doubleOrNull?.let { BigDecimal(it.toString()) }
                ?: contentOrNull?.toBigDecimalOrNull()?.let { BigDecimal(it.toPlainString()) }
        else -> null
    }

private fun JsonElement.asString(): String? =
    (this as? JsonPrimitive)?.contentOrNull
