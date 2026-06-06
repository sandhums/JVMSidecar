package com.atrius.sidecar.cql

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.interceptor.ThreadLocalCapturingInterceptor
import com.atrius.sidecar.api.EvaluateExpressionRequest
import com.atrius.sidecar.api.EvaluateExpressionResponse
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.HashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.hl7.fhir.instance.model.api.IBaseResource
import org.opencds.cqf.cql.engine.data.CompositeDataProvider
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import org.opencds.cqf.cql.engine.fhir.retrieve.RestFhirRetrieveProvider
import org.opencds.cqf.cql.engine.fhir.searchparam.SearchParameterResolver
private const val FHIR_MODEL_URI = "http://hl7.org/fhir"

/**
 * Evaluates ELM (JSON or XML) via CQ Framework [LibraryManager] + [LibrarySourceLoader]: translated
 * libraries should carry [org.hl7.cql_annotations.r1.CqlToElmInfo] (e.g. CQL Studio output) so binary
 * compatibility checks succeed.
 *
 * Performance: FHIR generic clients and KR `Library` resources are cached process-wide; hydrated
 * [PreparedLibraryStack] instances are cached per `(libraryBase, libraryId, version, includes)`;
 * ValueSet `$expand` results are cached per HTS base via [CachedR4FhirTerminologyProvider].
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

        val clinicalBase = trimBase(request.hfsBaseUrl)
        val terminologyBase = trimBase(request.htsBaseUrl)
        val libraryBase = effectiveLibraryBase(request)
        clearFhirCaptures(clinicalBase, terminologyBase, libraryBase)
        val fhirHttpCapture = SidecarFhirClients.captureForBase(clinicalBase)
        val krFetchBaseline = SidecarMetrics.currentKrLibraryFetches()
        val startedNs = System.nanoTime()
        var libraryCacheHit: Boolean? = null
        var error = false
        try {
            val inlineElm = request.elm?.takeIf { it.isNotBlank() }?.trim()
            if (inlineElm == null && !request.resolveLibraryArtifactsFromFhir) {
                throw IllegalArgumentException(
                    "elm must be provided unless resolveLibraryArtifactsFromFhir is true (primary loads from FHIR Library)",
                )
            }
            val (prepared, cacheHit) =
                try {
                    resolvePreparedStackWithMetrics(request, inlineElm)
                } catch (e: Exception) {
                    throw evaluationFailedException(
                        "ELM resolution failed (missing include payloads, classpath ELM, FHIR Library.content ELM, or incompatible translator metadata?).",
                        e,
                        fhirHttpCapture,
                        clinicalBase,
                    )
                }
            libraryCacheHit = cacheHit
            return evaluateInternal(request, clinicalBase, fhirHttpCapture, prepared)
        } catch (e: Exception) {
            error = true
            throw e
        } finally {
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            val krFetches = SidecarMetrics.krFetchesSince(krFetchBaseline)
            SidecarMetrics.recordEvaluate(
                durationMs = durationMs,
                libraryCacheHit = libraryCacheHit,
                libraryId = request.libraryId,
                libraryVersion = request.libraryVersion,
                expression = request.expression,
                krFetchesThisRequest = krFetches,
                error = error,
            )
            clearFhirCaptures(clinicalBase, terminologyBase, libraryBase)
        }
    }

    private fun evaluateInternal(
        request: EvaluateExpressionRequest,
        clinicalBaseForUrls: String,
        fhirHttpCapture: ThreadLocalCapturingInterceptor,
        prepared: PreparedLibraryStack,
    ): EvaluateExpressionResponse {
        val fhirContext = SidecarFhirClients.fhirContext()
        val clinicalClient =
            SidecarFhirClients.client(
                trimBase(request.hfsBaseUrl),
                request.fhirAuthorization?.accessToken,
            )
        val terminologyClient = SidecarFhirClients.client(trimBase(request.htsBaseUrl))

        val modelResolver = R4FhirModelResolver(fhirContext)
        val terminology =
            CachedR4FhirTerminologyProvider(
                terminologyClient,
                trimBase(request.htsBaseUrl),
            )
        val retrieveProvider =
            PrefetchRetrieveSupport.buildRetrieveProvider(
                fhirContext,
                modelResolver,
                clinicalClient,
                terminology,
                request.prefetch,
            )
        val composite = CompositeDataProvider(modelResolver, retrieveProvider)
        val dataProviders =
            HashMap<String?, org.opencds.cqf.cql.engine.data.DataProvider?>().apply {
                put(FHIR_MODEL_URI, composite)
                put(ATRIUS_IN_MODEL_URI, composite)
            }

        val environment = Environment(prepared.libraryManager, dataProviders, terminology)
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

        val parameterMap = engineParameters(prepared.primaryLibrary, request)

        val evalResults =
            try {
                engine.evaluate {
                    evaluationDateTime = evaluationTime
                    parameters = HashMap(parameterMap)
                    request.patientId?.let { pid ->
                        contextParameter = "Patient" to pid
                    }
                    library(prepared.libraryIdentifier) {
                        expressions(request.expression)
                    }
                }
            } catch (e: Exception) {
                throw evaluationFailedException("CQL engine.evaluate failed.", e, fhirHttpCapture, clinicalBaseForUrls)
            }

        if (evalResults.hasExceptions()) {
            val ex =
                evalResults.exceptions.values.firstOrNull()
                    ?: Exception("evaluation failed")
            throw evaluationFailedException("CQL evaluation produced exceptions.", ex, fhirHttpCapture, clinicalBaseForUrls)
        }

        val libraryResult =
            evalResults.getResultFor(prepared.libraryIdentifier)
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

    private fun resolvePreparedStackWithMetrics(
        request: EvaluateExpressionRequest,
        inlineElm: String?,
    ): Pair<PreparedLibraryStack, Boolean?> {
        if (inlineElm != null) {
            return buildPreparedLibraryStackFromInlineElm(request, inlineElm, request.elmFormat) to null
        }

        val cacheKey = evaluationCacheKey(request)
        if (cacheKey != null) {
            EvaluationLibraryCache.get(cacheKey)?.let { return it to true }
        }

        val libraryBase = effectiveLibraryBase(request)
        val libraryClient = SidecarFhirClients.client(libraryBase)
        val built = buildPreparedLibraryStackFromFhir(request, libraryClient, libraryBase)
        if (cacheKey != null) {
            EvaluationLibraryCache.put(cacheKey, built)
            return built to false
        }
        return built to null
    }

    private fun engineParameters(library: org.hl7.elm.r1.Library, request: EvaluateExpressionRequest): Map<String, Any?> {
        val params = request.parameters ?: return emptyMap()
        return convertEngineParameters(library, params)
    }

    private fun trimBase(url: String): String = url.trimEnd('/')

    private fun clearFhirCaptures(vararg bases: String) {
        for (base in bases) {
            SidecarFhirClients.captureForBase(base).clearThreadLocals()
        }
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

internal fun isFhirHttpTraceEnabled(): Boolean =
    java.lang.Boolean.getBoolean("sidecar.fhir.http.log") ||
        System.getenv("SIDECAR_FHIR_HTTP_LOG")?.equals("true", ignoreCase = true) == true
