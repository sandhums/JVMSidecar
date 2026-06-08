package com.atrius.sidecar.cql

import org.cqframework.cql.cql2elm.LibraryContentType
import org.hl7.fhir.r4.model.Library
import java.security.MessageDigest

/**
 * Fingerprint for KR `Library` resources so same `(id, version)` re-imports invalidate caches
 * when `meta.versionId` or ELM attachment bytes change (KR pinning slice 2).
 */
internal fun libraryContentIdentity(library: Library): String {
    library.meta?.versionId?.takeIf { it.isNotBlank() }?.let { return "vid:$it" }
    return "elm:${elmContentSha256(library)}"
}

internal fun elmContentSha256(library: Library): String {
    val bytes =
        pickElmAttachmentBytes(library, LibraryContentType.XML)
            ?: pickElmAttachmentBytes(library, LibraryContentType.JSON)
            ?: library.content
                .asSequence()
                .filter { it.hasData() }
                .map { it.data }
                .firstOrNull()
    if (bytes == null || bytes.isEmpty()) return "empty"
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
