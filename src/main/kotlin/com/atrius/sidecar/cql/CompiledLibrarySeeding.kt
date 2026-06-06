package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ElmFormat
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.model.CompiledLibrary
import org.cqframework.cql.cql2elm.tracking.Trackable.resultType
import org.hl7.elm.r1.IncludeDef
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.VersionedIdentifier

private val CLASSPATH_SEED_LIBRARIES =
    listOf(
        "FHIRHelpers" to "4.0.1",
        "FHIRHelpers" to "4.4.000",
    )

/**
 * Pre-translated ELM from the request, hydrated and inserted into [LibraryManager.compiledLibraries]
 * so CQ Framework skips binary-compatibility checks and CQL recompilation for inline payloads.
 */
internal fun seedCompiledLibrariesFromPayloads(
    libraryManager: LibraryManager,
    modelManager: ModelManager,
    payloads: List<ElmLibraryPayload>,
) {
    val readerProvider = hydratingElmLibraryReaderProvider(modelManager)
    for (payload in payloads) {
        seedCompiledLibrary(libraryManager, modelManager, payload.identifier) {
            readerProvider.create(elmMimeType(payload.storageFormat)).read(payload.rawPayload)
        }
    }
}

/**
 * Pre-load included libraries from KR as hydrated ELM so evaluation does not fall back to CQL
 * (CQL omits generated expressions like `define Patient` that ELM references).
 */
internal fun seedIncludedLibrariesFromFhir(
    libraryManager: LibraryManager,
    modelManager: ModelManager,
    loader: FhirLibraryElmLoader,
    root: Library,
) {
    val readerProvider = hydratingElmLibraryReaderProvider(modelManager)
    val pending = ArrayDeque<VersionedIdentifier>()
    val seen = mutableSetOf<Pair<String, String?>>()

    fun enqueueIncludes(library: Library) {
        library.includes?.def?.forEach { include ->
            includeToIdentifier(include)?.let { pending.addLast(it) }
        }
    }

    enqueueIncludes(root)
    while (pending.isNotEmpty()) {
        val requested = pending.removeFirst()
        val key = (requested.id ?: "") to requested.version?.takeIf { it.isNotBlank() }
        if (!seen.add(key)) continue

        val fhirLibrary = loader.loadLibrary(requested) ?: continue
        val (elmPayload, format) =
            try {
                decodePreferredElmString(fhirLibrary)
            } catch (_: Exception) {
                continue
            }

        val parsed =
            seedCompiledLibraryFromElm(
                libraryManager,
                modelManager,
                loader,
                requested,
                readerProvider,
                elmPayload,
                format,
            ) ?: continue
        enqueueIncludes(parsed)
    }
}

private fun seedCompiledLibraryFromElm(
    libraryManager: LibraryManager,
    modelManager: ModelManager,
    libraryLoader: FhirLibraryElmLoader,
    requested: VersionedIdentifier,
    readerProvider: org.cqframework.cql.elm.serializing.ElmLibraryReaderProvider,
    elmPayload: String,
    format: ElmFormat,
): Library? {
    val library =
        if (libraryManager.compiledLibraries.containsKey(requested)) {
            libraryManager.compiledLibraries[requested]?.library ?: return null
        } else {
            val parsed =
                try {
                    readerProvider.create(elmMimeType(format)).read(elmPayload)
                } catch (_: Exception) {
                    return null
                }
            ensureAtriusInModelInfo(modelManager, libraryLoader)
            resolveLibraryUsings(parsed, modelManager)
            val cacheKey = versionedIdentifierFrom(parsed.identifier ?: return null)
            try {
                libraryManager.compiledLibraries[cacheKey] = buildCompiledLibrary(parsed)
            } catch (_: Exception) {
                return null
            }
            parsed
        }
    return library
}

private fun includeToIdentifier(include: IncludeDef): VersionedIdentifier? {
    val id = include.path?.takeIf { it.isNotBlank() } ?: include.localIdentifier?.takeIf { it.isNotBlank() } ?: return null
    val raw =
        VersionedIdentifier().apply {
            this.id = id
            include.version?.takeIf { it.isNotBlank() }?.let { version = it }
        }
    return normalizeLibraryIdentifier(raw)
}

internal fun seedClasspathCompiledLibraries(
    libraryManager: LibraryManager,
    modelManager: ModelManager,
) {
    val provider = ClasspathElmLibraryProvider()
    val readerProvider = hydratingElmLibraryReaderProvider(modelManager)
    for ((id, version) in CLASSPATH_SEED_LIBRARIES) {
        val requested =
            VersionedIdentifier().apply {
                this.id = id
                this.version = version
            }
        val (source, contentType) =
            provider.getLibraryContent(requested, LibraryContentType.XML)?.let { it to LibraryContentType.XML }
                ?: provider.getLibraryContent(requested, LibraryContentType.JSON)?.let { it to LibraryContentType.JSON }
                ?: continue
        seedCompiledLibrary(libraryManager, modelManager, requested) {
            try {
                readerProvider.create(contentType.mimeType()).read(source)
            } finally {
                source.close()
            }
        }
    }
}

private fun seedCompiledLibrary(
    libraryManager: LibraryManager,
    modelManager: ModelManager,
    requested: VersionedIdentifier,
    readLibrary: () -> Library,
) {
    if (libraryManager.compiledLibraries.containsKey(requested)) return
    val library =
        try {
            readLibrary()
        } catch (_: Exception) {
            return
        }
    resolveLibraryUsings(library, modelManager)
    val cacheKey = versionedIdentifierFrom(library.identifier ?: return)
    try {
        libraryManager.compiledLibraries[cacheKey] = buildCompiledLibrary(library)
    } catch (_: Exception) {
        // Hydration may be incomplete for large helper libraries; CQL fallback still applies.
    }
}

private fun versionedIdentifierFrom(src: VersionedIdentifier): VersionedIdentifier =
    VersionedIdentifier().apply {
        id = src.id
        src.version?.let { version = it }
        src.system?.let { system = it }
    }

internal fun buildCompiledLibrary(library: Library): CompiledLibrary {
    val compiled = CompiledLibrary()
    compiled.library = library
    compiled.identifier = library.identifier
    library.usings?.def?.forEach { compiled.add(it) }
    library.includes?.def?.forEach { compiled.add(it) }
    library.codeSystems?.def?.forEach { compiled.add(it) }
    library.valueSets?.def?.forEach { compiled.add(it) }
    library.codes?.def?.forEach { compiled.add(it) }
    library.concepts?.def?.forEach { compiled.add(it) }
    library.parameters?.def?.forEach { compiled.add(it) }
    library.statements?.def?.forEach { stmt ->
        requireNotNull(stmt.resultType) {
            "Expression ${stmt.name} in library ${library.identifier?.id} does not have a result type."
        }
        compiled.add(stmt)
    }
    library.statements?.def?.sortBy { it.name }
    return compiled
}

internal fun elmMimeType(format: ElmFormat): String =
    when (format) {
        ElmFormat.JSON -> "application/elm+json"
        ElmFormat.XML -> "application/elm+xml"
        ElmFormat.AUTO -> error("AUTO must be resolved before seeding compiled libraries")
    }
