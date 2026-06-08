package com.atrius.sidecar.cql

import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.VersionedIdentifier
import java.util.concurrent.ConcurrentHashMap

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
    private val stacks = ConcurrentHashMap<String, PreparedLibraryStack>()

    fun get(key: String): PreparedLibraryStack? = stacks[key]

    fun put(key: String, stack: PreparedLibraryStack) {
        stacks[key] = stack
    }

    /** Drop all compiled ELM stacks (after KR library re-import or version bump). */
    fun clear(): Int {
        val n = stacks.size
        stacks.clear()
        return n
    }

    fun cacheKey(
        libraryBase: String,
        libraryId: String,
        libraryVersion: String?,
        primaryContentIdentity: String,
        includedLibrarySignatures: List<String>,
    ): String {
        val base = libraryBase.trimEnd('/')
        val ver = libraryVersion?.takeIf { it.isNotBlank() } ?: ""
        val includes = includedLibrarySignatures.sorted().joinToString(",")
        return "$base\u0000$libraryId\u0000$ver\u0000$primaryContentIdentity\u0000$includes"
    }
}

/** KR `Library` resources keyed by [cacheKey] per knowledge-repository base URL. */
internal object FhirLibraryResourceCaches {
    private val caches = ConcurrentHashMap<String, MutableMap<String, org.hl7.fhir.r4.model.Library>>()

    fun forBase(krBase: String): MutableMap<String, org.hl7.fhir.r4.model.Library> =
        caches.computeIfAbsent(krBase.trimEnd('/')) { ConcurrentHashMap() }

    /** Drop cached KR `Library` resources across all knowledge-repository bases. */
    fun clearAll(): Int {
        var n = 0
        for (bucket in caches.values) {
            n += bucket.size
            bucket.clear()
        }
        caches.clear()
        return n
    }
}
