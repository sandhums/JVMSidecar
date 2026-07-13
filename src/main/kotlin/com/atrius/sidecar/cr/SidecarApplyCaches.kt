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

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrLoad(key: String, loader: () -> T): T =
        byKey.computeIfAbsent(key) { loader() } as T

    fun clear(): Int {
        val n = byKey.size
        byKey.clear()
        return n
    }
}
