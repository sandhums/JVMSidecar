package com.atrius.sidecar.cql

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Library as FhirLibrary

/** Hydrated CQ stack reused across evaluate requests for the same primary library content on the same KR base. */
internal data class PreparedLibraryStack(
    val libraryManager: LibraryManager,
    val modelManager: ModelManager,
    val primaryLibrary: Library,
    val libraryIdentifier: VersionedIdentifier,
)

/**
 * Process-wide cache of compiled CQL libraries loaded from KR.
 * Clinical/terminology FHIR clients stay per-request; only the ELM graph is cached.
 */
internal object EvaluationLibraryCache {
    private val stacks = SidecarProcessCaches.stackCache<String, PreparedLibraryStack>()

    fun get(key: String): PreparedLibraryStack? = stacks.getIfPresent(key)

    fun put(key: String, stack: PreparedLibraryStack) {
        stacks.put(key, stack)
    }

    /** Drop stacks whose key starts with the same KR base / id / version (any content identity). */
    fun evictForPrimary(libraryBase: String, libraryId: String, libraryVersion: String?) {
        val prefix = cacheKeyPrefix(libraryBase, libraryId, libraryVersion)
        stacks.asMap().keys.filter { it.startsWith(prefix) }.forEach { stacks.invalidate(it) }
        stacks.cleanUp()
    }

    /** Drop all compiled ELM stacks (after KR library re-import or version bump). */
    fun clear(): Int = SidecarProcessCaches.invalidateAll(stacks)

    fun cacheKey(
        libraryBase: String,
        libraryId: String,
        libraryVersion: String?,
        primaryContentIdentity: String,
        includedLibrarySignatures: List<String>,
    ): String {
        val includes = includedLibrarySignatures.sorted().joinToString(",")
        return cacheKeyPrefix(libraryBase, libraryId, libraryVersion) + "$primaryContentIdentity\u0000$includes"
    }

    private fun cacheKeyPrefix(libraryBase: String, libraryId: String, libraryVersion: String?): String {
        val base = libraryBase.trimEnd('/')
        val ver = libraryVersion?.takeIf { it.isNotBlank() } ?: ""
        return "$base\u0000$libraryId\u0000$ver\u0000"
    }
}

/** KR `Library` resources keyed by logical id/version plus content identity, per KR base. */
internal object FhirLibraryResourceCaches {
    private val cache = SidecarProcessCaches.contentCache<String, FhirLibrary>()

    fun get(krBase: String, fullKey: String): FhirLibrary? =
        cache.getIfPresent(processKey(krBase, fullKey))

    fun findByLogical(krBase: String, logicalKey: String): FhirLibrary? {
        val prefix = processKey(krBase, "$logicalKey\u0000")
        return cache.asMap().entries.firstOrNull { it.key.startsWith(prefix) }?.value
    }

    fun put(krBase: String, fullKey: String, library: FhirLibrary) {
        cache.put(processKey(krBase, fullKey), library)
    }

    fun pruneLogical(krBase: String, logicalKey: String, exceptFullKey: String?) {
        val prefix = processKey(krBase, "$logicalKey\u0000")
        val except = exceptFullKey?.let { processKey(krBase, it) }
        cache.asMap().keys.filter { it.startsWith(prefix) && it != except }.forEach { cache.invalidate(it) }
        cache.cleanUp()
    }

    fun clearBase(krBase: String) {
        val prefix = "${krBase.trimEnd('/')}\u0000"
        cache.asMap().keys.filter { it.startsWith(prefix) }.forEach { cache.invalidate(it) }
        cache.cleanUp()
    }

    /** Drop cached KR `Library` resources across all knowledge-repository bases. */
    fun clearAll(): Int = SidecarProcessCaches.invalidateAll(cache)

    private fun processKey(krBase: String, fullKey: String): String =
        "${krBase.trimEnd('/')}\u0000$fullKey"
}
