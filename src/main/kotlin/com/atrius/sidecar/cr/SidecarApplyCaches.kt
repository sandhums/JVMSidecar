package com.atrius.sidecar.cr

import com.atrius.sidecar.cql.SidecarProcessCaches
import com.atrius.sidecar.cql.resourceContentIdentity
import org.hl7.fhir.instance.model.api.IBaseResource

/**
 * Process-wide caches for PlanDefinition `$apply` KR content and HTS `$expand`.
 *
 * Cache-first by logical key with a short TTL so same-version KR re-imports become visible
 * without a manual flush. Identity is stored so a fetch that sees a new versionId replaces
 * the entry immediately.
 */
internal object SidecarKrContentCache {
    private data class CachedContent(val identity: String, val resource: IBaseResource)

    private val byKey = SidecarProcessCaches.contentCache<String, CachedContent>()

    fun cacheKey(contentBase: String, resourceType: String, idPart: String): String =
        "${contentBase.trimEnd('/')}\u0000$resourceType\u0000$idPart"

    @Suppress("UNCHECKED_CAST")
    fun <T : IBaseResource> getOrLoad(key: String, loader: () -> T): T {
        byKey.getIfPresent(key)?.let { return it.resource as T }
        val loaded = loader()
        val identity = resourceContentIdentity(loaded)
        byKey.put(key, CachedContent(identity, loaded))
        return loaded
    }

    fun clear(): Int = SidecarProcessCaches.invalidateAll(byKey)
}

/** Cache FHIR `$expand` MethodOutcome / Parameters results keyed by ValueSet id + expand params. */
internal object SidecarExpandCache {
    private val byKey = SidecarProcessCaches.contentCache<String, Any>()

    fun cacheKey(terminologyBase: String, resourceType: String?, idPart: String?, op: String): String =
        "${terminologyBase.trimEnd('/')}\u0000${resourceType.orEmpty()}\u0000${idPart.orEmpty()}\u0000$op"

    /**
     * Cache successful non-empty expands only. Empty / failed expands are often transient
     * (HTS lag after KR import) and must not stick for the process lifetime.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrLoad(key: String, loader: () -> T): T {
        byKey.getIfPresent(key)?.let { return it as T }
        val loaded = loader()
        if (!isEmptyOrFailedExpand(loaded)) {
            byKey.put(key, loaded)
        }
        return loaded
    }

    fun clear(): Int = SidecarProcessCaches.invalidateAll(byKey)

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
