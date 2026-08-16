package com.atrius.sidecar.cr

import com.atrius.sidecar.api.EvaluateMeasureRequest
import com.atrius.sidecar.api.EvaluateMeasureResponse
import com.atrius.sidecar.cql.SidecarMetrics
import com.atrius.sidecar.cql.evaluationFailedException
import com.atrius.sidecar.fhir.sidecarCrSettings
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Parameters
import org.opencds.cqf.fhir.cr.measure.MeasureEvaluationOptions
import org.opencds.cqf.fhir.cr.measure.common.MeasurePeriodValidator
import org.opencds.cqf.fhir.cr.measure.common.MeasureReference
import org.opencds.cqf.fhir.cr.measure.r4.R4MultiMeasureService
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Executes FHIR R4 **`Measure/$evaluate-measure`** via [R4MultiMeasureService].
 *
 * Reuses the same KR / HFS / HTS routing, caches, allowlist, and prefetch flattening as `$apply`.
 */
class SidecarMeasureEvaluator {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun evaluate(request: EvaluateMeasureRequest): EvaluateMeasureResponse {
        require(request.patientId.isNotBlank()) { "patientId must not be blank" }
        require(
            !request.measureId.isNullOrBlank() || !request.measureUrl.isNullOrBlank(),
        ) { "measureId or measureUrl is required" }

        val startedNs = System.nanoTime()
        var error = false
        try {
            return evaluateInternal(request)
        } catch (e: Exception) {
            error = true
            throw e
        } finally {
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            SidecarMetrics.recordMeasure(
                durationMs,
                request.measureId ?: request.measureUrl,
                error,
            )
        }
    }

    private fun evaluateInternal(request: EvaluateMeasureRequest): EvaluateMeasureResponse {
        val runtime =
            openApplyRuntime(
                operation = "Measure/\$evaluate-measure",
                hfsBaseUrl = request.hfsBaseUrl,
                htsBaseUrl = request.htsBaseUrl,
                libraryBaseUrl = request.libraryBaseUrl,
                useServerData = request.useServerData,
                prefetch = request.prefetch,
                parameters = request.parameters,
                accessToken = request.fhirAuthorization?.accessToken,
                dataFromPrefetch = true,
            )

        val measureRef: MeasureReference =
            when {
                !request.measureUrl.isNullOrBlank() ->
                    MeasureReference.ByCanonicalUrl(request.measureUrl!!.trim())
                else ->
                    MeasureReference.ById(IdType("Measure", request.measureId!!.trim()))
            }

        val options =
            MeasureEvaluationOptions.defaultOptions()
                .setEvaluationSettings(sidecarCrSettings().evaluationSettings)
                .setEnsureSearchParameters(false)

        val service =
            R4MultiMeasureService(
                runtime.routingRepo,
                options,
                runtime.libraryBase,
                MeasurePeriodValidator(),
            )

        val subject = normalizeApplyReference(request.patientId, "Patient")!!
        val periodStart = parseMeasureInstant(request.periodStart, endOfDay = false)
        val periodEnd = parseMeasureInstant(request.periodEnd, endOfDay = true)
        val parameters = runtime.applyParameters as? Parameters

        val report =
            try {
                service.evaluate(
                    measureRef,
                    periodStart,
                    periodEnd,
                    request.reportType,
                    subject,
                    null,
                    parameters,
                    null,
                    null,
                )
            } catch (e: Exception) {
                throw evaluationFailedException(
                    "Measure/\$evaluate-measure failed:",
                    e,
                    runtime.fhirHttpCapture,
                    runtime.clinicalBase,
                )
            }

        val parser = runtime.fhirContext.newJsonParser()
        val reportElement = json.parseToJsonElement(parser.encodeResourceToString(report))
        val resultId =
            report.idElement?.idPart?.takeIf { it.isNotBlank() }

        return EvaluateMeasureResponse(
            measureId = request.measureId ?: resultId,
            measureReport = reportElement,
        )
    }
}

internal fun parseMeasureInstant(raw: String?, endOfDay: Boolean): ZonedDateTime? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        ZonedDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        try {
            val date = LocalDate.parse(text)
            if (endOfDay) {
                date.atTime(23, 59, 59).atZone(ZoneOffset.UTC)
            } else {
                date.atStartOfDay(ZoneOffset.UTC)
            }
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException(
                "periodStart/periodEnd must be ISO-8601 instant or date (e.g. 2026-01-01 or 2026-01-01T00:00:00Z)",
            )
        }
    }
}
