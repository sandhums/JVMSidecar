package com.atrius.sidecar.cql

import kotlinx.io.asSource
import kotlinx.io.buffered
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.createModelInfoProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Library
import java.util.WeakHashMap

internal const val ATRIUS_IN_MODEL_NAME = "AtriusIn"
internal const val ATRIUS_IN_MODEL_VERSION = "0.1.0"
internal const val ATRIUS_IN_MODEL_URI = "https://atrius.in/fhir/r4/atrius-in"
private const val ATRIUS_IN_MODELINFO_LIBRARY_ID = "AtriusIn-ModelInfo"
private const val MODELINFO_CLASSPATH = "/modelinfo/atriusin-modelinfo-0.1.0.xml"

private val registeredManagers = WeakHashMap<ModelManager, Boolean>()

/**
 * Registers AtriusIn modelinfo (classpath fallback, optional FHIR `Library/AtriusIn-ModelInfo`)
 * so ELM hydration can resolve profile types such as `ConditionEncounterDiagnosis`.
 */
internal fun ensureAtriusInModelInfo(modelManager: ModelManager, libraryLoader: FhirLibraryElmLoader?) {
    runCatching { modelManager.resolveModel(ATRIUS_IN_MODEL_NAME, ATRIUS_IN_MODEL_VERSION) }.getOrNull()?.let { return }

    if (registeredManagers.putIfAbsent(modelManager, true) == null) {
        val provider =
            createModelInfoProvider { id, _, version ->
                if (id != ATRIUS_IN_MODEL_NAME) return@createModelInfoProvider null
                if (!version.isNullOrBlank() && version != ATRIUS_IN_MODEL_VERSION) {
                    return@createModelInfoProvider null
                }
                openAtriusInModelInfoSource(libraryLoader)
            }
        modelManager.modelInfoLoader.registerModelInfoProvider(provider)
    }

    modelManager.resolveModel(ATRIUS_IN_MODEL_NAME, ATRIUS_IN_MODEL_VERSION)
    runCatching { modelManager.resolveModelByUri(ATRIUS_IN_MODEL_URI) }
}

private fun openAtriusInModelInfoSource(libraryLoader: FhirLibraryElmLoader?) =
    loadAtriusInModelInfoBytes(libraryLoader)?.inputStream()?.asSource()?.buffered()
        ?: ClasspathElmLibraryProvider::class.java.getResourceAsStream(MODELINFO_CLASSPATH)?.asSource()?.buffered()

private fun loadAtriusInModelInfoBytes(libraryLoader: FhirLibraryElmLoader?): ByteArray? {
    val loader = libraryLoader ?: return null
    val requested =
        VersionedIdentifier().apply {
            id = ATRIUS_IN_MODELINFO_LIBRARY_ID
            version = ATRIUS_IN_MODEL_VERSION
        }
    val library = loader.loadLibrary(requested) ?: return null
    return pickModelInfoAttachmentBytes(library)
}

/** Picks base64-decoded modelinfo XML from [Library.content] (`application/xml`). */
internal fun pickModelInfoAttachmentBytes(library: Library): ByteArray? {
    for (c in library.content) {
        val ct = c.contentType?.lowercase() ?: continue
        if (ct == "application/xml" || ct.startsWith("application/xml;")) {
            if (c.hasData()) return c.data
        }
    }
    return null
}
