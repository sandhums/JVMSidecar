package com.atrius.sidecar.cr

import ca.uhn.fhir.context.FhirContext
import com.atrius.sidecar.api.ApplyActivityDefinitionRequest
import com.atrius.sidecar.api.ApplyActivityDefinitionResponse
import com.atrius.sidecar.cql.PrefetchRetrieveSupport
import com.atrius.sidecar.cql.SidecarFhirClients
import com.atrius.sidecar.cql.SidecarMetrics
import com.atrius.sidecar.cql.evaluationFailedException
import com.atrius.sidecar.cql.requireLibraryBaseForApply
import com.atrius.sidecar.cql.trimFhirBase
import com.atrius.sidecar.fhir.sidecarCrSettings
import kotlinx.serialization.json.JsonElement
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.fhir.cql.LibraryEngine
import org.opencds.cqf.fhir.cr.activitydefinition.ActivityDefinitionProcessor
import org.opencds.cqf.fhir.utility.monad.Eithers
import org.opencds.cqf.fhir.utility.repository.RestRepository

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
        require(request.hfsBaseUrl.isNotBlank()) { "hfsBaseUrl must not be blank" }
        require(request.htsBaseUrl.isNotBlank()) { "htsBaseUrl must not be blank" }
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
        val libraryBase =
            requireLibraryBaseForApply(request.libraryBaseUrl, "ActivityDefinition/\$apply")
        val clinicalBase = trimFhirBase(request.hfsBaseUrl)
        val terminologyBase = trimFhirBase(request.htsBaseUrl)

        val fhirHttpCapture = SidecarFhirClients.captureForBase(clinicalBase)
        val fhirContext = SidecarFhirClients.fhirContext()
        val applyParameters = buildApplyParameters(fhirContext, request.parameters)

        val contentClient = SidecarFhirClients.client(libraryBase)
        val clinicalClient =
            SidecarFhirClients.client(clinicalBase, request.fhirAuthorization?.accessToken)
        val terminologyClient = SidecarFhirClients.client(terminologyBase)

        val prefetchBundle =
            if (request.useServerData) {
                null
            } else {
                prefetchToBundle(fhirContext, request.prefetch)
            }

        val dataRepo = RestRepository(clinicalClient)
        val contentRepo = RestRepository(contentClient)
        val terminologyRepo = RestRepository(terminologyClient)
        val routingRepo =
            SidecarRoutingRepository(
                fhirContext = fhirContext,
                data = dataRepo,
                content = contentRepo,
                terminology = terminologyRepo,
                contentBaseUrl = libraryBase,
                terminologyBaseUrl = terminologyBase,
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

        val crSettings = sidecarCrSettings()
        val processor = ActivityDefinitionProcessor(routingRepo, crSettings)
        // Same as PlanDefinition: avoid CQF ProxyRepository (null invoke(id,$expand)).
        val libraryEngine = LibraryEngine(routingRepo, crSettings.evaluationSettings)

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
                    applyParameters,
                    prefetchBundle,
                    libraryEngine,
                )
            } catch (e: Exception) {
                throw evaluationFailedException(
                    "ActivityDefinition/\$apply failed:",
                    e,
                    fhirHttpCapture,
                    clinicalBase,
                )
            }

        requireNotNull(result) { "ActivityDefinition/\$apply returned null; expected request resource" }

        val parser = fhirContext.newJsonParser()
        val resourceElement = json.parseToJsonElement(parser.encodeResourceToString(result))
        val resultId =
            (result as? Resource)?.idElement?.idPart?.takeIf { it.isNotBlank() }

        return ApplyActivityDefinitionResponse(
            activityDefinitionId = request.activityDefinitionId ?: resultId,
            resource = resourceElement,
        )
    }

    /**
     * Flatten CDS prefetch into a collection bundle; omit Patient (subject comes from `$apply` params).
     */
    private fun prefetchToBundle(
        fhirContext: FhirContext,
        prefetch: Map<String, JsonElement>?,
    ): Bundle? {
        val resources =
            PrefetchRetrieveSupport.dedupeResourcesByTypeAndId(
                PrefetchRetrieveSupport.flattenPrefetchResources(fhirContext, prefetch),
            )
        if (resources.isEmpty()) return null
        val bundle = Bundle()
        bundle.type = Bundle.BundleType.COLLECTION
        for (resource in resources) {
            if (resource is Resource && resource !is Patient) {
                bundle.addEntry().resource = resource
            }
        }
        return if (bundle.entry.isEmpty()) null else bundle
    }
}
