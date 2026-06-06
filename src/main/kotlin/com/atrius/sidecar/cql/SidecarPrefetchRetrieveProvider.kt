package com.atrius.sidecar.cql

import java.time.ZoneOffset
import java.util.Date
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.cql.engine.model.ModelResolver
import org.opencds.cqf.cql.engine.retrieve.RetrieveProvider
import org.opencds.cqf.cql.engine.retrieve.TerminologyAwareRetrieveProvider
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.runtime.DateTime
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo

/**
 * In-memory CDS Hooks prefetch retrieve for CQ Framework 4.8 (replaces legacy `PrefetchDataProviderR4`).
 *
 * Indexes prefetched resources by base FHIR type and resolves AtriusIn / QI-Core profile retrieve names
 * to those types via [typeTargets].
 */
internal class SidecarPrefetchRetrieveProvider(
    resources: List<Any>,
    private val modelResolver: ModelResolver,
    private val typeTargets: Map<String, String>,
) : TerminologyAwareRetrieveProvider() {

    private val prefetchByType: Map<String, List<Any>> = populateMap(resources)

    override fun retrieve(
        context: String?,
        contextPath: String?,
        contextValue: Any?,
        dataType: String,
        templateId: String?,
        codePath: String?,
        codes: Iterable<Code>?,
        valueSet: String?,
        datePath: String?,
        dateLowPath: String?,
        dateHighPath: String?,
        dateRange: Interval?,
    ): Iterable<Any?>? {
        if (codePath == null && (codes != null || valueSet != null)) {
            throw IllegalArgumentException(
                "A code path must be provided when filtering on codes or a valueset.",
            )
        }
        if (dataType.isBlank()) {
            throw IllegalArgumentException(
                "A data type (e.g. Condition, Encounter) must be specified for clinical data retrieval",
            )
        }
        if (context == "Patient" && contextPath == null) {
            return null
        }

        for (candidateType in resolveDataTypes(dataType)) {
            val match = retrieveForType(
                candidateType,
                codePath,
                codes,
                valueSet,
                datePath,
                dateLowPath,
                dateHighPath,
                dateRange,
            )
            if (match.isNotEmpty()) return match
        }
        return emptyList()
    }

    private fun resolveDataTypes(dataType: String): List<String> {
        val base = typeTargets[dataType]
        return if (base != null && base != dataType) listOf(dataType, base) else listOf(dataType)
    }

    private fun retrieveForType(
        dataType: String,
        codePath: String?,
        codes: Iterable<Code>?,
        valueSet: String?,
        datePath: String?,
        dateLowPath: String?,
        dateHighPath: String?,
        dateRange: Interval?,
    ): List<Any> {
        val resourcesOfType = prefetchByType[dataType] ?: return emptyList()
        if (resourcesOfType.isEmpty() || (dateRange == null && codePath == null)) {
            return resourcesOfType
        }

        var filterCodes = codes
        val terminology = terminologyProvider
        if (valueSet != null && terminology != null) {
            val vsId = valueSet.removePrefix("urn:oid:")
            filterCodes = terminology.expand(ValueSetInfo().withId(vsId))
        }

        val out = ArrayList<Any>()
        for (resource in resourcesOfType) {
            if (!matchesFilters(resource, codePath, filterCodes, datePath, dateLowPath, dateHighPath, dateRange)) {
                continue
            }
            out.add(resource)
        }
        return out
    }

    private fun matchesFilters(
        resource: Any,
        codePath: String?,
        codes: Iterable<Code>?,
        datePath: String?,
        dateLowPath: String?,
        dateHighPath: String?,
        dateRange: Interval?,
    ): Boolean {
        var include = true

        if (dateRange != null) {
            include =
                when {
                    datePath != null -> {
                        if (dateHighPath != null || dateLowPath != null) {
                            throw IllegalArgumentException(
                                "If datePath is specified, dateLowPath and dateHighPath must not be present.",
                            )
                        }
                        val dateObject = toR4DateTime(modelResolver.resolvePath(resource, datePath))
                        when (dateObject) {
                            is DateTime -> dateTimeOverlapsRange(dateObject, dateRange)
                            is Interval -> intervalsOverlap(dateObject, dateRange)
                            else -> false
                        }
                    }
                    else -> {
                        if (dateHighPath == null && dateLowPath == null) {
                            throw IllegalArgumentException(
                                "If datePath is not given, either lowDatePath or highDatePath must be provided.",
                            )
                        }
                        val low =
                            dateLowPath?.let {
                                toR4DateTime(modelResolver.resolvePath(resource, it)) as? DateTime
                            }
                        val high =
                            dateHighPath?.let {
                                toR4DateTime(modelResolver.resolvePath(resource, it)) as? DateTime
                            }
                        intervalsOverlap(Interval(low, true, high, true), dateRange)
                    }
                }
        }

        if (include && !codePath.isNullOrEmpty()) {
            if (codes != null) {
                val codeObject = toR4Codes(modelResolver.resolvePath(resource, codePath))
                include = codeObjectMatches(codes, codeObject)
            }
        }

        return include
    }

    private fun populateMap(resources: List<Any>): Map<String, List<Any>> {
        val map = LinkedHashMap<String, MutableList<Any>>()
        for (resource in resources) {
            if (resource !is Resource) continue
            map.getOrPut(resource.fhirType()) { ArrayList() }.add(resource)
        }
        return map
    }

    private fun toR4DateTime(value: Any?): Any? =
        when (value) {
            null -> null
            is Date -> javaDateToDateTime(value)
            is org.hl7.fhir.r4.model.DateTimeType -> javaDateToDateTime(value.value)
            is org.hl7.fhir.r4.model.InstantType -> javaDateToDateTime(value.value)
            is Period ->
                Interval(
                    javaDateToDateTime(value.start),
                    true,
                    javaDateToDateTime(value.end),
                    true,
                )
            is DateTime, is Interval -> value
            else -> value
        }

    private fun javaDateToDateTime(date: Date?): DateTime? {
        if (date == null) return null
        return DateTime(date.toInstant().atOffset(ZoneOffset.UTC))
    }

    private fun toR4Codes(value: Any?): List<Code> =
        when (value) {
            null -> emptyList()
            is org.hl7.fhir.r4.model.CodeType -> listOf(Code().withCode(value.value))
            is Coding -> listOf(Code().withSystem(value.system).withCode(value.code))
            is CodeableConcept ->
                value.coding.map { c -> Code().withSystem(c.system).withCode(c.code) }
            is Code -> listOf(value)
            is Iterable<*> -> {
                val out = ArrayList<Code>()
                for (item in value) {
                    out.addAll(toR4Codes(item))
                }
                out
            }
            else -> emptyList()
        }

    private fun codeObjectMatches(codes: Iterable<Code>, codeObject: List<Code>): Boolean {
        if (codeObject.isEmpty()) return false
        for (qualifying in codeObject) {
            for (code in codes) {
                val systemMatch =
                    qualifying.system == null || qualifying.system == code.system
                val codeMatch =
                    qualifying.code != null && qualifying.code == code.code
                if (systemMatch && codeMatch) return true
            }
        }
        return false
    }

    private fun dateTimeOverlapsRange(value: DateTime, range: Interval): Boolean {
        val start = range.start as? DateTime
        val end = range.end as? DateTime
        if (start != null && value.compareTo(start) < 0) return false
        if (end != null && value.compareTo(end) > 0) return false
        return true
    }

    private fun intervalsOverlap(left: Interval, right: Interval): Boolean {
        val leftStart = left.start as? DateTime
        val leftEnd = left.end as? DateTime
        val rightStart = right.start as? DateTime
        val rightEnd = right.end as? DateTime
        if (leftEnd != null && rightStart != null && leftEnd.compareTo(rightStart) < 0) return false
        if (rightEnd != null && leftStart != null && rightEnd.compareTo(leftStart) < 0) return false
        return true
    }
}

/** Try prefetch providers in order; use the first non-empty result. */
internal class PriorityRetrieveProvider(
    private vararg val providers: RetrieveProvider,
) : RetrieveProvider {

    override fun retrieve(
        context: String?,
        contextPath: String?,
        contextValue: Any?,
        dataType: String,
        templateId: String?,
        codePath: String?,
        codes: Iterable<Code>?,
        valueSet: String?,
        datePath: String?,
        dateLowPath: String?,
        dateHighPath: String?,
        dateRange: Interval?,
    ): Iterable<Any?>? {
        var last: Iterable<Any?>? = null
        for (provider in providers) {
            last =
                provider.retrieve(
                    context,
                    contextPath,
                    contextValue,
                    dataType,
                    templateId,
                    codePath,
                    codes,
                    valueSet,
                    datePath,
                    dateLowPath,
                    dateHighPath,
                    dateRange,
                )
            if (last != null) {
                val iter = last.iterator()
                if (iter.hasNext()) return last
            }
        }
        return last
    }
}
