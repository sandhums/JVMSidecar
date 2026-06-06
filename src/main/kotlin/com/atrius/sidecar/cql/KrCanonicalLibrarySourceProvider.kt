package com.atrius.sidecar.cql

import kotlinx.io.Source
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier

/**
 * Resolves Atrius canonical `include` identifiers (`https://atrius.in/fhir/r4/atrius-in/...`)
 * via KR **before** CQ Framework attempts outbound HTTP to the public URL.
 *
 * Register this provider **first** on [org.cqframework.cql.cql2elm.LibraryManager.librarySourceLoader].
 */
internal class KrCanonicalLibrarySourceProvider(
    private val delegate: FhirElmLibrarySourceProvider,
) : LibrarySourceProvider {

    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier): Source? =
        resolveCanonical(libraryIdentifier)?.let { delegate.getLibrarySource(it) }

    override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType): Source? =
        resolveCanonical(libraryIdentifier)?.let { delegate.getLibraryContent(it, type) }

    private fun resolveCanonical(libraryIdentifier: VersionedIdentifier): VersionedIdentifier? {
        if (!isAtriusCanonicalLibraryIdentifier(libraryIdentifier)) return null
        return normalizeLibraryIdentifier(libraryIdentifier)
    }
}
