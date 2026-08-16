package com.atrius.sidecar.cr

import com.atrius.sidecar.api.ApplyActivityDefinitionRequest
import com.atrius.sidecar.api.ApplyActivityDefinitionResponse
import com.atrius.sidecar.cql.SidecarMetrics
import com.atrius.sidecar.cql.evaluationFailedException
import com.atrius.sidecar.fhir.sidecarCrSettings
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.fhir.cr.activitydefinition.ActivityDefinitionProcessor
import org.opencds.cqf.fhir.utility.monad.Eithers

/**
 * Executes FHIR R4 **`ActivityDefinition/$apply`** via [ActivityDefinitionProcessor] from
 * [org.opencds.cqf.fhir:cqf-fhir-cr](https://github.com/cqframework/clinical-reasoning).
 *
 * CQF implements the FHIR apply algorithm: create target resource from [kind], map structural
 * elements, resolve participant/location from context, evaluate [dynamicValue] (CQL/FHIRPath with
 * `%parameter` context variables), and optional [transform] StructureMap.
 */
class SidecarActivityDefinitionApplier {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun apply(request: ApplyActivityDefinitionRequest): ApplyActivityDefinitionResponse {
        require(request.patientId.isNotBlank()) { "patientId must not be blank" }
        require(
            !request.activityDefinitionId.isNullOrBlank() ||
                !request.activityDefinitionUrl.isNullOrBlank(),
        ) { "activityDefinitionId or activityDefinitionUrl is required" }

        val startedNs = System.nanoTime()
        var error = false
        try {
            return applyInternal(request)
        } catch (e: Exception) {
            error = true
            throw e
        } finally {
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            SidecarMetrics.recordApply(
                durationMs,
                request.activityDefinitionId ?: request.activityDefinitionUrl,
                error,
            )
        }
    }

    private fun applyInternal(request: ApplyActivityDefinitionRequest): ApplyActivityDefinitionResponse {
        val runtime =
            openApplyRuntime(
                operation = "ActivityDefinition/\$apply",
                hfsBaseUrl = request.hfsBaseUrl,
                htsBaseUrl = request.htsBaseUrl,
                libraryBaseUrl = request.libraryBaseUrl,
                useServerData = request.useServerData,
                prefetch = request.prefetch,
                parameters = request.parameters,
                accessToken = request.fhirAuthorization?.accessToken,
            )

        val activityDefinitionRef:
            org.opencds.cqf.fhir.utility.monad.Either3<
                IPrimitiveType<String>,
                IIdType,
                IBaseResource,
                > =
            when {
                !request.activityDefinitionUrl.isNullOrBlank() ->
                    Eithers.forLeft3(CanonicalType(request.activityDefinitionUrl!!.trim()))
                else ->
                    Eithers.forMiddle3(
                        IdType("ActivityDefinition", request.activityDefinitionId!!.trim()),
                    )
            }

        val processor = ActivityDefinitionProcessor(runtime.routingRepo, sidecarCrSettings())

        val subject = normalizeApplyReference(request.patientId, "Patient")!!
        val encounter = normalizeApplyReference(request.encounterId, "Encounter")
        val practitioner = normalizeApplyReference(request.practitionerId, "Practitioner")
        val organization = normalizeApplyReference(request.organizationId, "Organization")
        val userType = parseCodeableConceptElement(request.userType)
        val userLanguage = parseCodeableConceptElement(request.userLanguage)
        val userTaskContext = parseCodeableConceptElement(request.userTaskContext)
        val setting = parseCodeableConceptElement(request.setting)
        val settingContext = parseCodeableConceptElement(request.settingContext)

        val result =
            try {
                processor.apply(
                    activityDefinitionRef,
                    subject,
                    encounter,
                    practitioner,
                    organization,
                    userType,
                    userLanguage,
                    userTaskContext,
                    setting,
                    settingContext,
                    runtime.applyParameters,
                    runtime.prefetchBundle,
                    runtime.libraryEngine,
                )
            } catch (e: Exception) {
                throw evaluationFailedException(
                    "ActivityDefinition/\$apply failed:",
                    e,
                    runtime.fhirHttpCapture,
                    runtime.clinicalBase,
                )
            }

        requireNotNull(result) { "ActivityDefinition/\$apply returned null; expected request resource" }

        val parser = runtime.fhirContext.newJsonParser()
        val resourceElement = json.parseToJsonElement(parser.encodeResourceToString(result))
        val resultId =
            (result as? Resource)?.idElement?.idPart?.takeIf { it.isNotBlank() }

        return ApplyActivityDefinitionResponse(
            activityDefinitionId = request.activityDefinitionId ?: resultId,
            resource = resourceElement,
        )
    }
}
