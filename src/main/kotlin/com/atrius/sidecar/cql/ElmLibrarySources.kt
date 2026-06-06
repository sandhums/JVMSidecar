package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ElmFormat
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier

internal data class ElmLibraryPayload(
    val identifier: VersionedIdentifier,
    /** Wire encoding ([ElmFormat.JSON] or [ElmFormat.XML]; never [ElmFormat.AUTO]). */
    val storageFormat: ElmFormat,
    val rawPayload: String,
)

/**
 * Serves ELM JSON/XML bytes for one or more libraries (primary + includes) through [LibrarySourceLoader].
 */
internal class MultiElmLibrarySourceProvider(
    private val libraries: List<ElmLibraryPayload>,
) : LibrarySourceProvider {
    init {
        require(libraries.isNotEmpty()) { "at least one ELM library payload required" }
    }

    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier) = null

    override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType) =
        libraries.firstOrNull { identifiersMatch(it.identifier, libraryIdentifier) }?.let { payload ->
            val formatMatches =
                when (type) {
                    LibraryContentType.JSON -> payload.storageFormat == ElmFormat.JSON
                    LibraryContentType.XML -> payload.storageFormat == ElmFormat.XML
                    else -> false
                }
            if (!formatMatches) {
                null
            } else {
                payload.rawPayload.encodeToByteArray().inputStream().asSource().buffered()
            }
        }

    private fun identifiersMatch(stored: VersionedIdentifier, requested: VersionedIdentifier): Boolean {
        if (stored.id != requested.id) return false
        val sv = stored.version
        val rv = requested.version
        if (sv.isNullOrBlank() || rv.isNullOrBlank()) return true
        return sv == rv
    }
}

/**
 * Fallback: classpath `/elm-libraries/{libraryId}-{version}.xml|json` then `{libraryId}.xml|json`.
 *
 * Drop ELM artifacts here for includes you do not send in [com.atrius.sidecar.api.EvaluateExpressionRequest.includedLibraries]
 * (for example `FHIRHelpers-4.0.1.xml`).
 */
internal class ClasspathElmLibraryProvider(
    private val resourceDirectory: String = "/elm-libraries/",
) : LibrarySourceProvider {
    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier) = null

    override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType): kotlinx.io.Source? =
        when (type) {
            LibraryContentType.XML ->
                openClasspathElm(libraryIdentifier, "xml")
                    ?: openClasspathElm(libraryIdentifier, "json")
            LibraryContentType.JSON ->
                openClasspathElm(libraryIdentifier, "json")
                    ?: openClasspathElm(libraryIdentifier, "xml")
            else -> null
        }

    private fun openClasspathElm(libraryIdentifier: VersionedIdentifier, ext: String): kotlinx.io.Source? {
        val id = libraryIdentifier.id ?: return null
        val ver = libraryIdentifier.version?.takeIf { it.isNotBlank() }
        val candidates =
            buildList {
                if (ver != null) add("$resourceDirectory$id-$ver.$ext")
                add("$resourceDirectory$id.$ext")
            }
        for (path in candidates) {
            val stream = ClasspathElmLibraryProvider::class.java.getResourceAsStream(path) ?: continue
            return stream.asSource().buffered()
        }
        return null
    }
}
