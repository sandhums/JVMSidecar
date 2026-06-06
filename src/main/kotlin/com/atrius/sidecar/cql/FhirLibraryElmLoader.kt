package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.StringClientParam
import com.atrius.sidecar.api.ElmFormat
import org.cqframework.cql.cql2elm.LibraryContentType
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Library

/**
 * Fetches R4 [Library] resources and caches them by [cacheKey] for reuse between primary prefetch and
 * [FhirElmLibrarySourceProvider].
 */
internal class FhirLibraryElmLoader(
    private val client: IGenericClient,
    krBase: String? = null,
    fetched: MutableMap<String, Library>? = null,
) {
    private val resourceCache: MutableMap<String, Library> =
        fetched ?: FhirLibraryResourceCaches.forBase(krBase ?: client.serverBase)

    fun loadLibrary(requested: VersionedIdentifier): Library? {
        val normalized = normalizeLibraryIdentifier(requested)
        val id = normalized.id?.takeIf { it.isNotBlank() } ?: return null
        val key = cacheKey(normalized)
        resourceCache[key]?.let { return it }
        val loaded = fetchLibraryUncached(id, normalized, requested) ?: return null
        resourceCache[key] = loaded
        return loaded
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
        val bundle =
            try {
                client.search<Bundle>().forResource(Library::class.java).where(
                    StringClientParam("url").matches().value(canonicalUrl),
                ).returnBundle(Bundle::class.java).execute()
            } catch (_: Exception) {
                return null
            }
        val reqVersion = requested.version?.takeIf { it.isNotBlank() }
        return bundle.entry.orEmpty().asSequence().mapNotNull { entry -> entry.resource as? Library }.firstOrNull { lib ->
            (lib.url == canonicalUrl || lib.name == requested.id) &&
                (reqVersion.isNullOrBlank() || versionsCompatible(lib.version, requested))
        }
    }

    private fun readLibraryById(id: String): Library? =
        try {
            client.read().resource(Library::class.java).withId(id).execute()
        } catch (_: Exception) {
            null
        }

    private fun searchLibraryByName(name: String, requested: VersionedIdentifier): Library? {
        val reqVersion = requested.version?.takeIf { it.isNotBlank() }
        var query =
            client.search<Bundle>().forResource(Library::class.java).where(
                StringClientParam("name").matches().value(name),
            )
        if (!reqVersion.isNullOrBlank()) {
            query = query.and(StringClientParam("version").matches().value(reqVersion))
        }
        val bundle =
            try {
                query.returnBundle(Bundle::class.java).execute()
            } catch (_: Exception) {
                return null
            }
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
