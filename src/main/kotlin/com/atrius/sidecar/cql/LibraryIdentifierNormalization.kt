package com.atrius.sidecar.cql

import org.hl7.elm.r1.VersionedIdentifier

private val CANONICAL_LIBRARY_URL_PREFIX = "https://atrius.in/fhir/r4/atrius-in/"
private val HTTP_LIBRARY_URL = Regex("^https?://")

/** True when [VersionedIdentifier] uses an Atrius canonical URL (not a local KR logical id). */
internal fun isAtriusCanonicalLibraryIdentifier(id: VersionedIdentifier): Boolean {
    val rawId = id.id?.takeIf { it.isNotBlank() } ?: return false
    if (rawId.startsWith(CANONICAL_LIBRARY_URL_PREFIX)) return true
    val system = id.system?.takeIf { it.isNotBlank() } ?: return false
    return system.startsWith(CANONICAL_LIBRARY_URL_PREFIX) || system == ATRIUS_IN_MODEL_URI
}

/**
 * Maps canonical Atrius library URLs to KR logical names (`FHIRHelpers`, `AtriusCommon`, …)
 * so resolution never calls `https://atrius.in/...` over the network.
 */
internal fun normalizeLibraryIdentifier(requested: VersionedIdentifier): VersionedIdentifier {
    val rawId = requested.id?.takeIf { it.isNotBlank() } ?: return requested

    val logicalName =
        when {
            rawId.startsWith(CANONICAL_LIBRARY_URL_PREFIX) ->
                rawId.removePrefix(CANONICAL_LIBRARY_URL_PREFIX).substringBefore('?').trim('/')
            HTTP_LIBRARY_URL.containsMatchIn(rawId) ->
                rawId.substringAfterLast('/').substringBefore('?')
            else -> null
        }?.takeIf { it.isNotBlank() }

    if (logicalName == null || logicalName == rawId) return requested

    return VersionedIdentifier().apply {
        id = logicalName
        requested.version?.takeIf { it.isNotBlank() }?.let { version = it }
        requested.system?.let { system = it }
    }
}
