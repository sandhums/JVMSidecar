package com.atrius.sidecar.cr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.client.interceptor.ThreadLocalCapturingInterceptor
import com.atrius.sidecar.config.SidecarEnv
import com.atrius.sidecar.cql.PrefetchRetrieveSupport
import com.atrius.sidecar.cql.SidecarFhirClients
import com.atrius.sidecar.cql.requireLibraryBaseForApply
import com.atrius.sidecar.fhir.sidecarCrSettings
import kotlinx.serialization.json.JsonElement
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.r4.model.Bundle
import org.opencds.cqf.fhir.cql.LibraryEngine
import org.opencds.cqf.fhir.utility.repository.InMemoryFhirRepository
import org.opencds.cqf.fhir.utility.repository.RestRepository

internal data class ApplyRuntime(
    val libraryBase: String,
    val clinicalBase: String,
    val terminologyBase: String,
    val fhirContext: FhirContext,
    val fhirHttpCapture: ThreadLocalCapturingInterceptor,
    val routingRepo: SidecarRoutingRepository,
    val libraryEngine: LibraryEngine,
    val applyParameters: IBaseParameters?,
    val prefetchBundle: Bundle?,
)

internal fun openApplyRuntime(
    operation: String,
    hfsBaseUrl: String,
    htsBaseUrl: String,
    libraryBaseUrl: String?,
    useServerData: Boolean,
    prefetch: Map<String, JsonElement>?,
    parameters: Map<String, JsonElement>?,
    accessToken: String?,
    /** When true, prefetch (if present) becomes the clinical data repository (Measure path). */
    dataFromPrefetch: Boolean = false,
): ApplyRuntime {
    val libraryBase =
        SidecarEnv.requireAllowedFhirBase(
            requireLibraryBaseForApply(libraryBaseUrl, operation),
            "libraryBaseUrl",
        )
    val clinicalBase = SidecarEnv.requireAllowedFhirBase(hfsBaseUrl, "hfsBaseUrl")
    val terminologyBase = SidecarEnv.requireAllowedFhirBase(htsBaseUrl, "htsBaseUrl")

    val fhirHttpCapture = SidecarFhirClients.captureForBase(clinicalBase)
    val fhirContext = SidecarFhirClients.fhirContext()
    val applyParameters = buildApplyParameters(parameters)

    val contentClient = SidecarFhirClients.client(libraryBase)
    val clinicalClient = SidecarFhirClients.client(clinicalBase, accessToken)
    val terminologyClient = SidecarFhirClients.client(terminologyBase)

    val prefetchBundle =
        if (useServerData) {
            null
        } else {
            PrefetchRetrieveSupport.prefetchToBundle(fhirContext, prefetch)
        }

    val dataRepo: IRepository =
        if (dataFromPrefetch && prefetchBundle != null) {
            InMemoryFhirRepository(fhirContext, prefetchBundle)
        } else {
            RestRepository(clinicalClient)
        }
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

    val crSettings = sidecarCrSettings()
    val libraryEngine = LibraryEngine(routingRepo, crSettings.evaluationSettings)
    return ApplyRuntime(
        libraryBase = libraryBase,
        clinicalBase = clinicalBase,
        terminologyBase = terminologyBase,
        fhirContext = fhirContext,
        fhirHttpCapture = fhirHttpCapture,
        routingRepo = routingRepo,
        libraryEngine = libraryEngine,
        applyParameters = applyParameters,
        prefetchBundle = prefetchBundle,
    )
}

internal fun planDefinitionCanonical(
    libraryBase: String,
    planDefinitionUrl: String?,
    planDefinitionId: String?,
): String? =
    planDefinitionUrl?.trim()?.takeIf { it.isNotBlank() }
        ?: planDefinitionId?.trim()?.takeIf { it.isNotBlank() }?.let {
            "${libraryBase.trimEnd('/')}/PlanDefinition/$it"
        }
