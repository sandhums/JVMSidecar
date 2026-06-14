package com.atrius.sidecar.cr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.EncodingEnum
import com.atrius.sidecar.fhir.newSidecarFhirContext
import com.atrius.sidecar.fhir.sidecarCrSettings
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor
import ca.uhn.fhir.rest.client.interceptor.ThreadLocalCapturingInterceptor
import com.atrius.sidecar.api.ApplyPlanDefinitionRequest
import com.atrius.sidecar.api.ApplyPlanDefinitionResponse
import com.atrius.sidecar.cql.PrefetchRetrieveSupport
import com.atrius.sidecar.cql.SidecarMetrics
import com.atrius.sidecar.cql.evaluationFailedException
import kotlinx.serialization.json.JsonElement
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.RequestGroup
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.fhir.cr.plandefinition.PlanDefinitionProcessor
import org.opencds.cqf.fhir.utility.monad.Eithers
import org.opencds.cqf.fhir.utility.repository.RestRepository
import org.slf4j.LoggerFactory

/**
 * Executes FHIR R4 **`PlanDefinition/$apply`** via [PlanDefinitionProcessor] from
 * [org.opencds.cqf.fhir:cqf-fhir-cr](https://github.com/cqframework/clinical-reasoning).
 *
 * Repository routing (Atrius stack):
 * - **content** → [ApplyPlanDefinitionRequest.libraryBaseUrl] (KR, PlanDefinition + Library)
 * - **data** → [ApplyPlanDefinitionRequest.hfsBaseUrl] (cr-fhir-bridge for QI-Core clinical data)
 * - **terminology** → [ApplyPlanDefinitionRequest.htsBaseUrl] (HTS)
 *
 * Prefetched CDS Hooks resources are loaded into an in-memory repository overlay when [ApplyPlanDefinitionRequest.useServerData]
 * is false (default).
 */
class SidecarPlanDefinitionApplier {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun apply(request: ApplyPlanDefinitionRequest): ApplyPlanDefinitionResponse {
        require(request.patientId.isNotBlank()) { "patientId must not be blank" }
        require(request.hfsBaseUrl.isNotBlank()) { "hfsBaseUrl must not be blank" }
        require(request.htsBaseUrl.isNotBlank()) { "htsBaseUrl must not be blank" }
        require(
            !request.planDefinitionId.isNullOrBlank() || !request.planDefinitionUrl.isNullOrBlank(),
        ) { "planDefinitionId or planDefinitionUrl is required" }

        val startedNs = System.nanoTime()
        var error = false
        try {
            return applyInternal(request)
        } catch (e: Exception) {
            error = true
            throw e
        } finally {
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            SidecarMetrics.recordApply(durationMs, request.planDefinitionId, error)
        }
    }

    private fun applyInternal(request: ApplyPlanDefinitionRequest): ApplyPlanDefinitionResponse {
        val fhirHttpCapture = ThreadLocalCapturingInterceptor()
        val fhirContext = newSidecarFhirContext()
        val applyParameters = buildApplyParameters(fhirContext, request.parameters)

        val libraryBase = trimBase(
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

        val planDefinitionRef: org.opencds.cqf.fhir.utility.monad.Either3<
            IPrimitiveType<String>,
            IIdType,
            IBaseResource,
            > =
            when {
                !request.planDefinitionUrl.isNullOrBlank() ->
                    Eithers.forLeft3(CanonicalType(request.planDefinitionUrl!!.trim()))
                else ->
                    Eithers.forMiddle3(
                        IdType("PlanDefinition", request.planDefinitionId!!.trim()),
                    )
            }

        val crSettings = sidecarCrSettings()
        val processor = PlanDefinitionProcessor(routingRepo, crSettings)

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
                    planDefinitionRef,
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
                    null,
                    dataRepo,
                    contentRepo,
                    terminologyRepo,
                )
            } catch (e: Exception) {
                throw evaluationFailedException(
                    "PlanDefinition/\$apply failed:",
                    e,
                    fhirHttpCapture,
                    clinicalBase,
                )
            }

        val (carePlan, requestGroup) = normalizeApplyResult(result, subject)

        val parser = fhirContext.newJsonParser()
        val carePlanElement = json.parseToJsonElement(parser.encodeResourceToString(carePlan))
        val requestGroupElement = json.parseToJsonElement(parser.encodeResourceToString(requestGroup))

        return ApplyPlanDefinitionResponse(
            planDefinitionId = request.planDefinitionId ?: carePlan.id ?: requestGroup.id,
            carePlan = carePlanElement,
            requestGroup = requestGroupElement,
        )
    }

    /**
     * CDS Hooks prefetch values are usually a Patient resource plus searchset [Bundle]s per key.
     * [InMemoryFhirRepository] indexes by `(resourceType, id)`; nesting multiple Bundle resources
     * (all with null id) causes `Duplicate key null`. Flatten like evaluate/expression prefetch.
     *
     * **Patient is omitted** from the overlay. Per FHIR `PlanDefinition/$apply`, the evaluation
     * subject is the required **`subject`** parameter (e.g. `Patient/cms165-demo`) — wired here as
     * [ApplyPlanDefinitionRequest.patientId] → [PlanDefinitionProcessor.apply] subject argument; CQF
     * resolves that reference from the clinical data repository. CDS Hooks prefetch `patient` is
     * chart context for the EHR, not a second `$apply` subject. Duplicating Patient in the data
     * bundle yields two subjects and breaks CQL `context Patient`. Legacy `evaluate/expression`
     * binds the same subject via `contextParameter = Patient/{id}`.
     */
    private fun prefetchToBundle(
        fhirContext: FhirContext,
        prefetch: Map<String, JsonElement>?,
    ): Bundle? {
        val resources = PrefetchRetrieveSupport.flattenPrefetchResources(fhirContext, prefetch)
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

/**
 * Normalize CQF `$apply` output to spec shape: primary [CarePlan] with activity → [RequestGroup].
 */
internal fun normalizeApplyResult(result: IBaseResource?, subjectReference: String): Pair<CarePlan, RequestGroup> {
    requireNotNull(result) { "PlanDefinition/\$apply returned null; expected CarePlan" }
    return when (result) {
        is CarePlan -> result to extractRequestGroup(result)
        is RequestGroup -> wrapRequestGroupInCarePlan(result, subjectReference) to result
        else ->
            error(
                "PlanDefinition/\$apply returned ${result.fhirType()}; expected CarePlan or RequestGroup",
            )
    }
}

/** Wrap a bare [RequestGroup] in a minimal [CarePlan] per FHIR `$apply` return shape. */
internal fun wrapRequestGroupInCarePlan(requestGroup: RequestGroup, subjectReference: String): CarePlan {
    val localId = requestGroup.idElement?.idPart?.takeIf { it.isNotBlank() } ?: "request-group"
    if (requestGroup.idElement?.idPart.isNullOrBlank()) {
        requestGroup.id = localId
    }
    return CarePlan().apply {
        status = CarePlan.CarePlanStatus.ACTIVE
        intent = CarePlan.CarePlanIntent.PROPOSAL
        subject = Reference(subjectReference)
        addContained(requestGroup)
        addActivity().reference = Reference("#$localId")
    }
}

/**
 * Resolve the [RequestGroup] from CQF `$apply` output.
 *
 * Per FHIR, [CarePlan.activity] links to request resources via [Reference] (RequestGroup,
 * ServiceRequest, Task, …) — not only via `contained`. CQF initially builds
 * `activity.reference = #{requestGroupId}` plus `CarePlan.contained` for the orchestration; some
 * paths return a bare [RequestGroup] directly. This helper accepts either shape.
 */
internal fun extractRequestGroup(result: IBaseResource?): RequestGroup {
    requireNotNull(result) { "PlanDefinition/\$apply returned null; expected RequestGroup" }
    if (result is RequestGroup) return result

    if (result is CarePlan) {
        result.contained.filterIsInstance<RequestGroup>().singleOrNull()?.let { return it }

        for (activity in result.activity) {
            val ref = activity.reference?.reference?.trim().orEmpty()
            if (ref.startsWith("#")) {
                val localId = ref.removePrefix("#")
                result.contained
                    .firstOrNull { it.idElement?.idPart == localId && it is RequestGroup }
                    ?.let { return it as RequestGroup }
            }
        }

        val containedGroups = result.contained.filterIsInstance<RequestGroup>()
        if (containedGroups.size == 1) return containedGroups.first()
    }

    error(
        "PlanDefinition/\$apply returned ${result.fhirType()}; expected RequestGroup or CarePlan containing RequestGroup",
    )
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
