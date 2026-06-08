package com.atrius.sidecar.cql

import com.atrius.sidecar.api.EvaluateExpressionRequest
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.VersionedIdentifier

internal fun evaluationCacheKey(
    request: EvaluateExpressionRequest,
    primaryContentIdentity: String,
): String? {
    if (!request.elm.isNullOrBlank()) return null
    if (!request.resolveLibraryArtifactsFromFhir) return null
    return EvaluationLibraryCache.cacheKey(
        effectiveLibraryBase(request),
        request.libraryId,
        request.libraryVersion,
        primaryContentIdentity,
        request.includedLibraries.map { "${it.libraryId}|${it.libraryVersion?.takeIf { v -> v.isNotBlank() } ?: ""}" },
    )
}

internal fun versionedIdentifierFromRequest(request: EvaluateExpressionRequest): VersionedIdentifier =
    VersionedIdentifier().apply {
        id = request.libraryId
        request.libraryVersion?.takeIf { it.isNotBlank() }?.let { version = it }
    }

internal fun buildPreparedLibraryStackFromFhir(
    request: EvaluateExpressionRequest,
    libraryLoader: FhirLibraryElmLoader,
    libraryBase: String,
    preloadedPrimary: org.hl7.fhir.r4.model.Library? = null,
): PreparedLibraryStack {
    val vid = versionedIdentifierFromRequest(request)
    val resource =
        preloadedPrimary
            ?: libraryLoader.loadLibrary(vid)
            ?: throw IllegalArgumentException(
                "FHIR Library not found for libraryId '${request.libraryId}'" +
                    (request.libraryVersion?.takeIf { it.isNotBlank() }?.let { v -> " version '$v'" } ?: "") +
                    "; supply elm inline or ensure GET Library/${request.libraryId} or Library?name=… succeeds",
            )
    val (primaryElmPayload, primaryElmFormat) = decodePreferredElmString(resource)
    val primaryLibrary = readElmLibrary(primaryElmPayload, primaryElmFormat)
    val payloads = buildElmPayloads(request, primaryElmPayload, primaryElmFormat, primaryLibrary)
    return buildPreparedLibraryStack(
        libraryLoader = libraryLoader,
        payloads = payloads,
        primaryLibrary = primaryLibrary,
        compilerOptions = cqlCompilerOptionsForFhirLibrary(primaryLibrary),
    )
}

internal fun buildPreparedLibraryStackFromInlineElm(
    request: EvaluateExpressionRequest,
    primaryElmPayload: String,
    primaryElmFormat: com.atrius.sidecar.api.ElmFormat,
): PreparedLibraryStack {
    val primaryLibrary = readElmLibrary(primaryElmPayload, primaryElmFormat)
    val payloads = buildElmPayloads(request, primaryElmPayload, primaryElmFormat, primaryLibrary)
    return buildPreparedLibraryStack(
        libraryLoader = null,
        payloads = payloads,
        primaryLibrary = primaryLibrary,
        compilerOptions = cqlCompilerOptionsFromElmLibrary(primaryLibrary),
    )
}

private fun buildElmPayloads(
    request: EvaluateExpressionRequest,
    primaryElmPayload: String,
    primaryElmFormat: com.atrius.sidecar.api.ElmFormat,
    primaryLibrary: Library,
): List<ElmLibraryPayload> {
    val primaryIdentifierRaw =
        primaryLibrary.identifier
            ?: throw IllegalArgumentException("ELM library must declare an identifier")

    if (primaryIdentifierRaw.id != request.libraryId) {
        throw IllegalArgumentException(
            "libraryId '${request.libraryId}' does not match ELM identifier id '${primaryIdentifierRaw.id}'",
        )
    }
    request.libraryVersion?.let { requested ->
        val embedded = primaryIdentifierRaw.version
        if (!embedded.isNullOrBlank() && embedded != requested) {
            throw IllegalArgumentException(
                "libraryVersion '$requested' does not match ELM identifier version '$embedded'",
            )
        }
    }

    val libraryIdentifier = versionedIdentifierFrom(primaryIdentifierRaw)
    val primaryStorage = resolveElmStorageFormat(primaryElmPayload, primaryElmFormat)
    return buildList {
        add(ElmLibraryPayload(libraryIdentifier, primaryStorage, primaryElmPayload))
        val seen = mutableSetOf(libraryKey(libraryIdentifier))
        for (inc in request.includedLibraries) {
            require(inc.elm.isNotBlank()) { "includedLibraries[].elm must not be blank" }
            require(inc.libraryId.isNotBlank()) { "includedLibraries[].libraryId must not be blank" }
            val lib = readElmLibrary(inc.elm, inc.elmFormat)
            val emb = lib.identifier ?: throw IllegalArgumentException("included ELM must declare an identifier")
            if (emb.id != inc.libraryId) {
                throw IllegalArgumentException(
                    "included libraryId '${inc.libraryId}' does not match ELM id '${emb.id}'",
                )
            }
            inc.libraryVersion?.let { v ->
                val ev = emb.version
                if (!ev.isNullOrBlank() && ev != v) {
                    throw IllegalArgumentException(
                        "included libraryVersion '$v' does not match ELM version '$ev'",
                    )
                }
            }
            val vid = versionedIdentifierFrom(emb)
            val key = libraryKey(vid)
            if (!seen.add(key)) {
                throw IllegalArgumentException("duplicate included library ${vid.id} version ${vid.version}")
            }
            if (key == libraryKey(libraryIdentifier)) {
                throw IllegalArgumentException("includedLibraries must not duplicate the primary library")
            }
            add(ElmLibraryPayload(vid, resolveElmStorageFormat(inc.elm, inc.elmFormat), inc.elm))
        }
    }
}

private fun buildPreparedLibraryStack(
    libraryLoader: FhirLibraryElmLoader?,
    payloads: List<ElmLibraryPayload>,
    primaryLibrary: Library,
    compilerOptions: org.cqframework.cql.cql2elm.CqlCompilerOptions,
): PreparedLibraryStack {
    val libraryIdentifier = payloads.first().identifier

    val modelManager = ModelManager()
    ensureAtriusInModelInfo(modelManager, libraryLoader)
    resolveLibraryUsings(primaryLibrary, modelManager)
    val libraryManager =
        LibraryManager(
            modelManager,
            compilerOptions,
            elmLibraryReaderProvider = hydratingElmLibraryReaderProvider(modelManager),
        )
    libraryManager.librarySourceLoader.registerProvider(MultiElmLibrarySourceProvider(payloads))
    libraryManager.librarySourceLoader.registerProvider(ClasspathElmLibraryProvider())
    libraryLoader?.let { loader ->
        val fhirProvider = FhirElmLibrarySourceProvider(loader)
        libraryManager.librarySourceLoader.registerProvider(KrCanonicalLibrarySourceProvider(fhirProvider))
        libraryManager.librarySourceLoader.registerProvider(fhirProvider)
    }

    if (libraryLoader != null) {
        seedCompiledLibrariesFromPayloads(libraryManager, modelManager, payloads)
        seedIncludedLibrariesFromFhir(libraryManager, modelManager, libraryLoader, primaryLibrary)
    }

    try {
        libraryManager.resolveLibrary(libraryIdentifier)
    } catch (e: Exception) {
        throw IllegalStateException(
            "ELM resolution failed (missing include payloads, classpath ELM, FHIR Library.content ELM, or incompatible translator metadata?).",
            e,
        )
    }

    return PreparedLibraryStack(
        libraryManager = libraryManager,
        modelManager = modelManager,
        primaryLibrary = primaryLibrary,
        libraryIdentifier = libraryIdentifier,
    )
}

private fun versionedIdentifierFrom(src: VersionedIdentifier): VersionedIdentifier =
    VersionedIdentifier().apply {
        id = src.id
        src.version?.let { version = it }
        src.system?.let { system = it }
    }

private fun libraryKey(id: VersionedIdentifier): Pair<String, String?> =
    (id.id ?: "") to id.version?.takeIf { it.isNotBlank() }

internal fun effectiveLibraryBase(request: EvaluateExpressionRequest): String =
    (request.libraryBaseUrl?.takeIf { it.isNotBlank() } ?: request.hfsBaseUrl).trimEnd('/')
