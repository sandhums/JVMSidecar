package com.atrius.sidecar.cr

import org.hl7.fhir.instance.model.api.IBaseResource
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide caches for PlanDefinition `$apply` KR content and HTS `$expand`.
 *
 * The evaluate/expression path already caches libraries and ValueSet expansions; `$apply`
 * historically re-fetched PlanDefinition / ActivityDefinition / Library and re-expanded
 * ValueSets on every request (~10s for HF admission). These caches close that gap.
 */
internal object SidecarKrContentCache {
    private val byKey = ConcurrentHashMap<String, IBaseResource>()

    fun cacheKey(contentBase: String, resourceType: String, idPart: String): String =
        "${contentBase.trimEnd('/')}\u0000$resourceType\u0000$idPart"

    @Suppress("UNCHECKED_CAST")
    fun <T : IBaseResource> getOrLoad(key: String, loader: () -> T): T =
        byKey.computeIfAbsent(key) { loader() } as T

    fun clear(): Int {
        val n = byKey.size
        byKey.clear()
        return n
    }
}

/** Cache FHIR `$expand` MethodOutcome / Parameters results keyed by ValueSet id. */
internal object SidecarExpandCache {
    private val byKey = ConcurrentHashMap<String, Any>()

    fun cacheKey(terminologyBase: String, resourceType: String?, idPart: String?, op: String): String =
        "${terminologyBase.trimEnd('/')}\u0000${resourceType.orEmpty()}\u0000${idPart.orEmpty()}\u0000$op"

    /**
     * Cache successful non-empty expands only. Empty / failed expands are often transient
     * (HTS lag after KR import) and must not stick for the process lifetime.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrLoad(key: String, loader: () -> T): T {
        byKey[key]?.let { return it as T }
        val loaded = loader()
        if (!isEmptyOrFailedExpand(loaded)) {
            byKey.putIfAbsent(key, loaded)
        }
        return loaded
    }

    fun clear(): Int {
        val n = byKey.size
        byKey.clear()
        return n
    }

    internal fun isEmptyOrFailedExpand(result: Any): Boolean =
        when (result) {
            is org.hl7.fhir.r4.model.ValueSet ->
                result.expansion?.contains.isNullOrEmpty()
            is org.hl7.fhir.r4.model.Parameters -> {
                val vs =
                    result.parameter
                        ?.firstOrNull { it.name == "return" || it.resource is org.hl7.fhir.r4.model.ValueSet }
                        ?.resource as? org.hl7.fhir.r4.model.ValueSet
                vs?.expansion?.contains.isNullOrEmpty()
            }
            is ca.uhn.fhir.rest.api.MethodOutcome ->
                result.resource == null ||
                    (result.resource is org.hl7.fhir.r4.model.ValueSet &&
                        (result.resource as org.hl7.fhir.r4.model.ValueSet).expansion?.contains.isNullOrEmpty())
            else -> false
        }
}
