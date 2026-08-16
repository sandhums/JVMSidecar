package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.StringClientParam
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import com.atrius.sidecar.api.ElmFormat
import org.cqframework.cql.cql2elm.LibraryContentType
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Library

internal fun interface LibraryHttpFetch {
    fun fetch(
        logicalId: String,
        normalized: VersionedIdentifier,
        original: VersionedIdentifier,
    ): Library?
}

/**
 * Fetches R4 [Library] resources and caches them by logical id/version plus [libraryContentIdentity]
 * for reuse between primary prefetch and [FhirElmLibrarySourceProvider].
 *
 * [loadLibrary] is cache-first (includes). [loadLibraryFresh] always hits KR so evaluate can
 * detect same-version re-imports via content identity.
 */
internal class FhirLibraryElmLoader(
    private val client: IGenericClient? = null,
    krBase: String? = null,
    fetched: MutableMap<String, Library>? = null,
    private val fetchOverride: LibraryHttpFetch? = null,
) {
    private val krBase: String =
        krBase?.trimEnd('/')
            ?: client?.serverBase?.trimEnd('/')
            ?: "local"
    private val localCache: MutableMap<String, Library>? = fetched

    fun loadLibrary(requested: VersionedIdentifier): Library? = loadLibrary(requested, forceRefresh = false)

    fun loadLibraryFresh(requested: VersionedIdentifier): Library? = loadLibrary(requested, forceRefresh = true)

    private fun loadLibrary(requested: VersionedIdentifier, forceRefresh: Boolean): Library? {
        val normalized = normalizeLibraryIdentifier(requested)
        val id = normalized.id?.takeIf { it.isNotBlank() } ?: return null
        val logicalKey = libraryLogicalCacheKey(normalized)
        if (!forceRefresh) {
            cachedForLogicalKey(logicalKey)?.let { return it }
        }
        val previous = if (forceRefresh) cachedForLogicalKey(logicalKey) else null
        SidecarMetrics.recordKrLibraryFetch()
        val loaded = doFetch(id, normalized, requested) ?: return null
        val identity = libraryContentIdentity(loaded)
        val fullKey = libraryResourceCacheKey(logicalKey, identity)
        if (previous != null && libraryContentIdentity(previous) != identity) {
            EvaluationLibraryCache.evictForPrimary(this.krBase, normalized.id ?: id, normalized.version)
            if (localCache == null) {
                FhirLibraryResourceCaches.clearBase(this.krBase)
            } else {
                pruneStaleResourceCacheEntries(logicalKey, except = null)
            }
        }
        pruneStaleResourceCacheEntries(logicalKey, except = fullKey)
        putCached(fullKey, loaded)
        return loaded
    }

    private fun cachedForLogicalKey(logicalKey: String): Library? {
        val prefix = "$logicalKey\u0000"
        if (localCache != null) {
            return localCache.entries.firstOrNull { it.key.startsWith(prefix) }?.value
        }
        return FhirLibraryResourceCaches.findByLogical(krBase, logicalKey)
    }

    private fun putCached(fullKey: String, library: Library) {
        if (localCache != null) {
            localCache[fullKey] = library
        } else {
            FhirLibraryResourceCaches.put(krBase, fullKey, library)
        }
    }

    private fun pruneStaleResourceCacheEntries(logicalKey: String, except: String?) {
        if (localCache != null) {
            val prefix = "$logicalKey\u0000"
            localCache.keys.filter { it.startsWith(prefix) && it != except }.forEach { localCache.remove(it) }
        } else {
            FhirLibraryResourceCaches.pruneLogical(krBase, logicalKey, except)
        }
    }

    private fun doFetch(
        logicalId: String,
        normalized: VersionedIdentifier,
        original: VersionedIdentifier,
    ): Library? {
        fetchOverride?.let { override ->
            return fhirReadOrNull { override.fetch(logicalId, normalized, original) }
        }
        return fetchLibraryUncached(logicalId, normalized, original)
    }

    private fun fetchLibraryUncached(
        logicalId: String,
        normalized: VersionedIdentifier,
        original: VersionedIdentifier,
    ): Library? {
        readLibraryById(logicalId)?.let { lib ->
            if (libraryMatchesRequest(lib, normalized)) return lib
        }
        searchLibraryByName(logicalId, normalized)?.let { return it }
        if (isAtriusCanonicalLibraryIdentifier(original)) {
            val canonicalUrl = original.id?.takeIf { it.isNotBlank() } ?: return null
            return searchLibraryByCanonicalUrl(canonicalUrl, normalized)
        }
        return null
    }

    private fun searchLibraryByCanonicalUrl(canonicalUrl: String, requested: VersionedIdentifier): Library? {
        val fhirClient = client ?: return null
        val bundle =
            fhirReadOrNull {
                fhirClient.search<Bundle>().forResource(Library::class.java).where(
                    StringClientParam("url").matches().value(canonicalUrl),
                ).returnBundle(Bundle::class.java).execute()
            } ?: return null
        val reqVersion = requested.version?.takeIf { it.isNotBlank() }
        return bundle.entry.orEmpty().asSequence().mapNotNull { entry -> entry.resource as? Library }.firstOrNull { lib ->
            (lib.url == canonicalUrl || lib.name == requested.id) &&
                (reqVersion.isNullOrBlank() || versionsCompatible(lib.version, requested))
        }
    }

    private fun readLibraryById(id: String): Library? {
        val fhirClient = client ?: return null
        return fhirReadOrNull {
            fhirClient.read().resource(Library::class.java).withId(id).execute()
        }
    }

    private fun searchLibraryByName(name: String, requested: VersionedIdentifier): Library? {
        val fhirClient = client ?: return null
        val reqVersion = requested.version?.takeIf { it.isNotBlank() }
        var query =
            fhirClient.search<Bundle>().forResource(Library::class.java).where(
                StringClientParam("name").matches().value(name),
            )
        if (!reqVersion.isNullOrBlank()) {
            query = query.and(StringClientParam("version").matches().value(reqVersion))
        }
        val bundle =
            fhirReadOrNull {
                query.returnBundle(Bundle::class.java).execute()
            } ?: return null
        return bundle.entry.orEmpty().asSequence().mapNotNull { entry -> entry.resource as? Library }.firstOrNull { lib ->
            lib.name == name && versionsCompatible(lib.version, requested)
        }
    }

    private fun libraryMatchesRequest(lib: Library, requested: VersionedIdentifier): Boolean {
        val idMatches = lib.name == requested.id || lib.idElement?.idPart == requested.id
        if (!idMatches) return false
        return versionsCompatible(lib.version, requested)
    }
}

/**
 * 404 / [ResourceNotFoundException] → null (try the next lookup). Other FHIR HTTP errors propagate
 * so KR 500s become evaluation failures instead of "library not found".
 */
internal fun <T> fhirReadOrNull(block: () -> T): T? {
    try {
        return block()
    } catch (_: ResourceNotFoundException) {
        return null
    } catch (e: BaseServerResponseException) {
        if (e.statusCode == 404) return null
        throw e
    }
}

/** Picks base64-decoded CQL source from [Library.content] (`text/cql`). */
internal fun pickCqlAttachmentBytes(library: Library): ByteArray? {
    for (c in library.content) {
        val ct = c.contentType?.lowercase() ?: continue
        if (ct == "text/cql" || ct.startsWith("text/cql;")) {
            if (c.hasData()) return c.data
        }
    }
    return null
}

/**
 * Prefer ELM XML attachment, then ELM JSON. Throws if neither exists (`text/cql` is not compiled).
 */
internal fun decodePreferredElmString(library: Library): Pair<String, ElmFormat> {
    pickElmAttachmentBytes(library, LibraryContentType.XML)?.let { bytes ->
        return String(bytes, Charsets.UTF_8) to ElmFormat.XML
    }
    pickElmAttachmentBytes(library, LibraryContentType.JSON)?.let { bytes ->
        return String(bytes, Charsets.UTF_8) to ElmFormat.JSON
    }
    val label = library.idElement?.idPart ?: library.name ?: "(unknown)"
    throw IllegalArgumentException(
        "FHIR Library '$label' has no ELM attachment (application/elm+xml or application/elm+json); text/cql is not compiled in the sidecar",
    )
}
