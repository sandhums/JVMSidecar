package com.atrius.sidecar.cr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.EncodingEnum
import com.atrius.sidecar.api.ApplyActivityDefinitionRequest
import com.atrius.sidecar.api.ApplyActivityDefinitionResponse
import com.atrius.sidecar.cql.PrefetchRetrieveSupport
import com.atrius.sidecar.cql.SidecarMetrics
import com.atrius.sidecar.cql.evaluationFailedException
import com.atrius.sidecar.fhir.newSidecarFhirContext
import com.atrius.sidecar.fhir.sidecarCrSettings
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor
import ca.uhn.fhir.rest.client.interceptor.ThreadLocalCapturingInterceptor
import kotlinx.serialization.json.JsonElement
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.fhir.cr.activitydefinition.ActivityDefinitionProcessor
import org.opencds.cqf.fhir.utility.monad.Eithers
import org.opencds.cqf.fhir.utility.repository.RestRepository
import org.slf4j.LoggerFactory

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
        val fhirHttpCapture = ThreadLocalCapturingInterceptor()
        val fhirContext = newSidecarFhirContext()
        val applyParameters = buildApplyParameters(fhirContext, request.parameters)

        val libraryBase =
            trimBase(
                request.libraryBaseUrl?.takeIf { it.isNotBlank() } ?: request.hfsBaseUrl,
            )
        val clinicalBase = trimBase(request.hfsBaseUrl)
        val terminologyBase = trimBase(request.htsBaseUrl)

        val contentClient =
            fhirContext.newRestfulGenericClient(libraryBase).configureSidecarFhirClient(fhirHttpCapture)
        val clinicalClient =
            fhirContext
                .newRestfulGenericClient(clinicalBase)
                .configureSidecarFhirClient(fhirHttpCapture)
                .configureClinicalBearerAuth(request.fhirAuthorization?.accessToken)
        val terminologyClient =
            fhirContext.newRestfulGenericClient(terminologyBase).configureSidecarFhirClient(fhirHttpCapture)

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
                    request.useServerData,
                    prefetchBundle,
                    dataRepo,
                    contentRepo,
                    terminologyRepo,
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

    private fun trimBase(url: String): String = url.trimEnd('/')
}

private val fhirHttpTraceLogger = LoggerFactory.getLogger("com.atrius.sidecar.fhir.http")

private fun isFhirHttpTraceEnabled(): Boolean =
    java.lang.Boolean.getBoolean("sidecar.fhir.http.log") ||
        System.getenv("SIDECAR_FHIR_HTTP_LOG")?.equals("true", ignoreCase = true) == true

private fun IGenericClient.configureClinicalBearerAuth(accessToken: String?): IGenericClient {
    val token = accessToken?.trim()?.takeIf { it.isNotEmpty() }
    if (token != null) {
        registerInterceptor(BearerTokenAuthInterceptor(token))
    }
    return this
}

private fun IGenericClient.configureSidecarFhirClient(capture: ThreadLocalCapturingInterceptor?): IGenericClient {
    capture?.let { registerInterceptor(it) }
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
