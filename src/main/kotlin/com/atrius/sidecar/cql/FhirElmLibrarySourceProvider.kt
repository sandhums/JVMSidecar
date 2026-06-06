package com.atrius.sidecar.cql

import java.io.ByteArrayInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Library

/**
 * Loads translated ELM from FHIR R4 [Library.content] when inline and classpath providers miss.
 *
 * Uses [FhirLibraryElmLoader] so primary prefetch can populate the same cache (avoid duplicate GETs).
 *
 * ELM attachments (`application/elm+xml`, `application/elm+json`) are preferred. When CQ Framework
 * rejects pre-translated ELM (binary compatibility), it falls back to [getLibrarySource] — we serve
 * **`text/cql`** from the same FHIR [Library] so includes like FHIRHelpers 4.4.000 can compile.
 */
internal class FhirElmLibrarySourceProvider(
    private val loader: FhirLibraryElmLoader,
) : LibrarySourceProvider {

    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier): kotlinx.io.Source? =
        getLibraryContent(libraryIdentifier, LibraryContentType.CQL)

    override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType): kotlinx.io.Source? {
        if (libraryIdentifier.id.isNullOrBlank()) return null

        if (type == LibraryContentType.CQL) {
            val library = loader.loadLibrary(libraryIdentifier) ?: return null
            val bytes = pickCqlAttachmentBytes(library) ?: return null
            return ByteArrayInputStream(bytes).asSource().buffered()
        }

        if (type != LibraryContentType.JSON && type != LibraryContentType.XML) return null

        val library = loader.loadLibrary(libraryIdentifier) ?: return null
        val bytes =
            pickElmAttachmentBytes(library, type)
                ?: pickElmAttachmentBytes(
                    library,
                    if (type == LibraryContentType.JSON) LibraryContentType.XML else LibraryContentType.JSON,
                )
                ?: return null
        return ByteArrayInputStream(bytes).asSource().buffered()
    }
}

internal fun cacheKey(vid: VersionedIdentifier): String =
    "${vid.id ?: ""}\u0000${vid.version?.takeIf { it.isNotBlank() } ?: ""}"

internal fun versionsCompatible(resourceVersion: String?, requested: VersionedIdentifier): Boolean {
    val req = requested.version?.takeIf { it.isNotBlank() } ?: return true
    val res = resourceVersion?.takeIf { it.isNotBlank() } ?: return true
    return res == req
}

/** Picks base64-decoded ELM bytes from [Library.content] for the requested CQ content type. */
internal fun pickElmAttachmentBytes(library: Library, type: LibraryContentType): ByteArray? {
    val preferredMime =
        when (type) {
            LibraryContentType.XML ->
                listOf(
                    "application/elm+xml",
                    "application/xml",
                    "text/xml",
                )
            LibraryContentType.JSON ->
                listOf(
                    "application/elm+json",
                    "application/json",
                )
            else -> return null
        }

    fun mimeMatches(actual: String?, desired: String): Boolean {
        if (actual.isNullOrBlank()) return false
        val lower = actual.lowercase()
        val d = desired.lowercase()
        return lower == d || lower.startsWith("$d;") || lower.startsWith("$d ")
    }

    for (desired in preferredMime) {
        for (c in library.content) {
            val ct = c.contentType ?: continue
            if (!mimeMatches(ct, desired)) continue
            if (!c.hasData()) continue
            return c.data
        }
    }
    return null
}
