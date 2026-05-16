package com.atrius.sidecar.cql

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.EncodingEnum
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor
import com.atrius.sidecar.api.ElmFormat
import com.atrius.sidecar.api.EvaluateExpressionRequest
import com.atrius.sidecar.api.EvaluateExpressionResponse
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.HashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.instance.model.api.IBaseResource
import org.opencds.cqf.cql.engine.data.CompositeDataProvider
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import org.opencds.cqf.cql.engine.fhir.retrieve.RestFhirRetrieveProvider
import org.opencds.cqf.cql.engine.fhir.searchparam.SearchParameterResolver
import org.opencds.cqf.cql.engine.fhir.terminology.R4FhirTerminologyProvider
import org.slf4j.LoggerFactory

class EvaluationFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private const val FHIR_MODEL_URI = "http://hl7.org/fhir"

/**
 * Evaluates ELM (JSON or XML) via CQ Framework [LibraryManager] + [LibrarySourceLoader]: translated
 * libraries should carry [org.hl7.cql_annotations.r1.CqlToElmInfo] (e.g. CQL Studio output) so binary
 * compatibility checks succeed.
 *
 * [com.atrius.sidecar.api.EvaluateExpressionRequest.includedLibraries] supplies ELM for `include`
 * targets; optional classpath fallback under `/elm-libraries/` (see [ClasspathElmLibraryProvider]);
 * optional FHIR **`Library.content`** fetch from [com.atrius.sidecar.api.EvaluateExpressionRequest.libraryBaseUrl]
 * when [com.atrius.sidecar.api.EvaluateExpressionRequest.resolveLibraryArtifactsFromFhir] is true (see [FhirLibraryElmLoader], [FhirElmLibrarySourceProvider]).
 * Inline **`elm`** always wins for the **primary** library when non-blank.
 *
 * The CQ engine uses **ca.uhn.fhir** only as an **HTTP client** ([FhirContext], [ca.uhn.fhir.rest.client.api.IGenericClient]) —
 * your clinical and terminology endpoints can be Helios, HAPI, or any server that speaks FHIR REST. Point
 * [com.atrius.sidecar.api.EvaluateExpressionRequest.hfsBaseUrl] and [com.atrius.sidecar.api.EvaluateExpressionRequest.htsBaseUrl]
 * at the URL prefix where `Patient`, `Condition`, `ValueSet`, etc. are reachable for your deployment (with or without a `/fhir` path).
 *
 * HAPI exposes [ca.uhn.fhir.context.FhirContext.newRestfulGenericClient] on the JVM; there is no
 * stable Kotlin extension named `restfulGenericClient` on the classpath we use.
 *
 * For Patient context, [RestFhirRetrieveProvider] / [org.opencds.cqf.cql.engine.fhir.retrieve.BaseFhirQueryGenerator]
 * expect the context value to be the **patient id string** (see `getContextParam` → `contextValue as String`),
 * not a [org.hl7.fhir.r4.model.Patient] instance.
 *
 * Creating each `IGenericClient` uses HAPI’s default server validation ([ca.uhn.fhir.rest.client.api.ServerValidationModeEnum],
 * typically `ONCE`), which issues `GET /metadata` against [com.atrius.sidecar.api.EvaluateExpressionRequest.hfsBaseUrl],
 * [com.atrius.sidecar.api.EvaluateExpressionRequest.htsBaseUrl], and (when
 * [com.atrius.sidecar.api.EvaluateExpressionRequest.resolveLibraryArtifactsFromFhir] is true)
 * the effective library base ([com.atrius.sidecar.api.EvaluateExpressionRequest.libraryBaseUrl] or
 * [com.atrius.sidecar.api.EvaluateExpressionRequest.hfsBaseUrl]) — ensure those URLs serve valid CapabilityStatements.
 *
 * All FHIR generic clients use [EncodingEnum.JSON] so requests prefer FHIR JSON (some servers differ from curl’s default `Accept`).
 *
 * Debugging outbound FHIR HTTP: `-Dsidecar.fhir.http.log=true` or env `SIDECAR_FHIR_HTTP_LOG=true`, with logger
 * `com.atrius.sidecar.fhir.http` at INFO (HAPI [LoggingInterceptor] summaries).
 */
class SidecarEvaluator {

    fun evaluate(request: EvaluateExpressionRequest): EvaluateExpressionResponse {
        require(request.libraryId.isNotBlank()) { "libraryId must not be blank" }
        require(request.expression.isNotBlank()) { "expression must not be blank" }
        require(request.hfsBaseUrl.isNotBlank()) { "hfsBaseUrl must not be blank" }
        require(request.htsBaseUrl.isNotBlank()) { "htsBaseUrl must not be blank" }

        val inlineElm = request.elm?.takeIf { it.isNotBlank() }?.trim()
        if (inlineElm == null && !request.resolveLibraryArtifactsFromFhir) {
            throw IllegalArgumentException(
                "elm must be provided unless resolveLibraryArtifactsFromFhir is true (primary loads from FHIR Library)",
            )
        }

        val fhirContext = FhirContext.forR4()
        val libraryCache = mutableMapOf<String, org.hl7.fhir.r4.model.Library>()
        val libraryClient =
            if (request.resolveLibraryArtifactsFromFhir) {
                fhirContext.newRestfulGenericClient(effectiveLibraryBase(request)).configureSidecarFhirClient()
            } else {
                null
            }
        val libraryLoader = libraryClient?.let { FhirLibraryElmLoader(it, libraryCache) }

        val primaryResolved: Pair<String, ElmFormat> =
            if (inlineElm != null) {
                inlineElm to request.elmFormat
            } else {
                val vid = versionedIdentifierFromRequest(request)
                val loader =
                    libraryLoader
                        ?: throw IllegalArgumentException(
                            "resolveLibraryArtifactsFromFhir must be true to load primary from FHIR",
                        )
                val resource =
                    loader.loadLibrary(vid)
                        ?: throw IllegalArgumentException(
                            "FHIR Library not found for libraryId '${request.libraryId}'" +
                                (request.libraryVersion?.takeIf { it.isNotBlank() }?.let { v -> " version '$v'" } ?: "") +
                                "; supply elm inline or ensure GET Library/${request.libraryId} or Library?name=… succeeds",
                        )
                decodePreferredElmString(resource)
            }

        val primaryElmPayload = primaryResolved.first
        val primaryElmFormat = primaryResolved.second
        val primaryLibrary = readElmLibrary(primaryElmPayload, primaryElmFormat)
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
        val payloads =
            buildList {
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

        val modelManager = ModelManager()
        val compilerOptions = cqlCompilerOptionsFromElmLibrary(primaryLibrary)
        val libraryManager =
            LibraryManager(
                modelManager,
                compilerOptions,
                elmLibraryReaderProvider = hydratingElmLibraryReaderProvider(modelManager),
            )
        libraryManager.librarySourceLoader.registerProvider(MultiElmLibrarySourceProvider(payloads))
        libraryManager.librarySourceLoader.registerProvider(ClasspathElmLibraryProvider())
        libraryLoader?.let { libraryManager.librarySourceLoader.registerProvider(FhirElmLibrarySourceProvider(it)) }

        try {
            libraryManager.resolveLibrary(libraryIdentifier)
        } catch (e: Exception) {
            throw EvaluationFailedException(
                "ELM resolution failed (missing include payloads, classpath ELM, FHIR Library.content ELM, or incompatible translator metadata?). ${e.toDetailedEvaluationMessage()}",
                e,
            )
        }

        val clinicalClient =
            fhirContext.newRestfulGenericClient(trimBase(request.hfsBaseUrl)).configureSidecarFhirClient()
        val terminologyClient =
            fhirContext.newRestfulGenericClient(trimBase(request.htsBaseUrl)).configureSidecarFhirClient()

        val modelResolver = R4FhirModelResolver(fhirContext)
        val terminology = R4FhirTerminologyProvider(terminologyClient)
        val retrieveProvider =
            RestFhirRetrieveProvider(
                SearchParameterResolver(fhirContext),
                modelResolver,
                clinicalClient,
            )
        val composite = CompositeDataProvider(modelResolver, retrieveProvider)
        val dataProviders =
            HashMap<String?, org.opencds.cqf.cql.engine.data.DataProvider?>().apply {
                put(FHIR_MODEL_URI, composite)
            }

        val environment = Environment(libraryManager, dataProviders, terminology)
        val engine = CqlEngine(environment)

        val evaluationTime =
            request.evaluationDateTime?.let {
                try {
                    ZonedDateTime.parse(it)
                } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException("evaluationDateTime must be ISO-8601 (e.g. 2026-05-13T12:00:00Z)")
                }
            }
                ?: ZonedDateTime.now()

        val parameterMap = engineParameters(request)

        val evalResults =
            try {
                engine.evaluate {
                    evaluationDateTime = evaluationTime
                    parameters = HashMap(parameterMap)
                    request.patientId?.let { pid ->
                        contextParameter = "Patient" to pid
                    }
                    library(libraryIdentifier) {
                        expressions(request.expression)
                    }
                }
            } catch (e: Exception) {
                throw EvaluationFailedException(e.toDetailedEvaluationMessage(), e)
            }

        if (evalResults.hasExceptions()) {
            val ex =
                evalResults.exceptions.values.firstOrNull()
                    ?: Exception("evaluation failed")
            throw EvaluationFailedException(ex.toDetailedEvaluationMessage(), ex)
        }

        val libraryResult =
            evalResults.getResultFor(libraryIdentifier)
                ?: throw EvaluationFailedException("no evaluation result for library ${request.libraryId}")

        val expressionResult =
            libraryResult[request.expression]
                ?: throw EvaluationFailedException("no result for expression '${request.expression}'")

        val rawValue = expressionResult.value
        val encoded = encodeEngineValue(fhirContext, rawValue)

        return EvaluateExpressionResponse(
            expression = request.expression,
            resultType = rawValue?.javaClass?.name,
            result = encoded,
        )
    }

    private fun versionedIdentifierFromRequest(request: EvaluateExpressionRequest): VersionedIdentifier =
        VersionedIdentifier().apply {
            id = request.libraryId
            request.libraryVersion?.takeIf { it.isNotBlank() }?.let { version = it }
        }

    private fun versionedIdentifierFrom(src: VersionedIdentifier): VersionedIdentifier =
        VersionedIdentifier().apply {
            id = src.id
            src.version?.let { version = it }
            src.system?.let { system = it }
        }

    private fun libraryKey(id: VersionedIdentifier): Pair<String, String?> =
        (id.id ?: "") to id.version?.takeIf { it.isNotBlank() }

    private fun engineParameters(request: EvaluateExpressionRequest): Map<String, Any?> {
        val params = request.parameters ?: return emptyMap()
        return params.mapValues { (_, v) -> jsonElementToEngine(v) }
    }

    private fun trimBase(url: String): String = url.trimEnd('/')

    private fun effectiveLibraryBase(request: EvaluateExpressionRequest): String =
        trimBase(request.libraryBaseUrl?.takeIf { it.isNotBlank() } ?: request.hfsBaseUrl)

    private fun jsonElementToEngine(element: JsonElement): Any? =
        when (element) {
            JsonNull -> null
            is JsonPrimitive -> {
                element.booleanOrNull?.let { return it }
                element.longOrNull?.let { return it }
                element.doubleOrNull?.let { return it }
                element.contentOrNull
            }
            is JsonArray -> element.map { jsonElementToEngine(it) }
            else -> element.toString()
        }

    private fun encodeEngineValue(ctx: FhirContext, value: Any?): JsonElement {
        if (value == null) return JsonNull
        if (value is IBaseResource) {
            val json = ctx.newJsonParser().encodeResourceToString(value)
            return JsonPrimitive(json)
        }
        if (value is Iterable<*>) {
            return JsonArray(value.map { encodeEngineValue(ctx, it) })
        }
        if (value is Array<*>) {
            return JsonArray(value.map { encodeEngineValue(ctx, it) })
        }
        when (value) {
            is Boolean -> return JsonPrimitive(value)
            is Number -> return JsonPrimitive(value.toString())
            is String -> return JsonPrimitive(value)
            is Char -> return JsonPrimitive(value.toString())
        }
        return JsonPrimitive(value.toString())
    }
}

private val fhirHttpTraceLogger = LoggerFactory.getLogger("com.atrius.sidecar.fhir.http")

private fun isFhirHttpTraceEnabled(): Boolean =
    java.lang.Boolean.getBoolean("sidecar.fhir.http.log") ||
        System.getenv("SIDECAR_FHIR_HTTP_LOG")?.equals("true", ignoreCase = true) == true

private fun IGenericClient.configureSidecarFhirClient(): IGenericClient {
    encoding = EncodingEnum.JSON
    if (!isFhirHttpTraceEnabled()) return this
    registerInterceptor(
        LoggingInterceptor(false).apply {
            setLogger(fhirHttpTraceLogger)
            setLogRequestSummary(true)
            setLogResponseSummary(true)
            setLogRequestHeaders(true)
            setLogResponseHeaders(false)
            setLogRequestBody(false)
            setLogResponseBody(false)
        },
    )
    return this
}
