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
    private val fetched: MutableMap<String, Library>,
) {
    fun loadLibrary(requested: VersionedIdentifier): Library? {
        val id = requested.id?.takeIf { it.isNotBlank() } ?: return null
        val key = cacheKey(requested)
        fetched[key]?.let { return it }
        val loaded = fetchLibraryUncached(id, requested) ?: return null
        fetched[key] = loaded
        return loaded
    }

    private fun fetchLibraryUncached(logicalId: String, requested: VersionedIdentifier): Library? {
        readLibraryById(logicalId)?.let { lib ->
            if (libraryMatchesRequest(lib, requested)) return lib
        }
        return searchLibraryByName(logicalId, requested)
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
