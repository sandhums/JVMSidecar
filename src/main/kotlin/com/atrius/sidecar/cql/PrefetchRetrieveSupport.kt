package com.atrius.sidecar.cql

import ca.uhn.fhir.context.FhirContext
import kotlinx.serialization.json.JsonElement
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.opencds.cqf.cql.engine.fhir.retrieve.RestFhirRetrieveProvider
import org.opencds.cqf.cql.engine.fhir.searchparam.SearchParameterResolver
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.model.ModelResolver
import org.opencds.cqf.cql.engine.retrieve.RetrieveProvider
import org.slf4j.LoggerFactory

/**
 * CDS Hooks prefetch overlay for CQL `retrieve` during `evaluate/expression`.
 *
 * Prefetched resources are indexed by base FHIR type. AtriusIn / QI-Core profile retrieve names
 * (`ConditionEncounterDiagnosis`, …) resolve to those types before client-side filtering.
 */
internal object PrefetchRetrieveSupport {

    private val logger = LoggerFactory.getLogger(PrefetchRetrieveSupport::class.java)

    private val profileRetrieveTargets: Map<String, String> by lazy { loadProfileRetrieveTargets() }

    fun buildRetrieveProvider(
        fhirContext: FhirContext,
        modelResolver: ModelResolver,
        clinicalClient: ca.uhn.fhir.rest.client.api.IGenericClient,
        terminology: TerminologyProvider,
        prefetch: Map<String, JsonElement>?,
    ): RetrieveProvider {
        val remote =
            RestFhirRetrieveProvider(
                SearchParameterResolver(fhirContext),
                modelResolver,
                clinicalClient,
            )
        remote.terminologyProvider = terminology
        remote.setExpandValueSets(true)

        val resources = flattenPrefetchResources(fhirContext, prefetch)
        if (resources.isEmpty()) {
            logger.debug("evaluate/expression: no prefetch resources; using REST retrieve only")
            return remote
        }

        val counts =
            resources
                .filterIsInstance<IBaseResource>()
                .groupingBy { it.fhirType() }
                .eachCount()
        logger.info(
            "evaluate/expression: prefetch overlay enabled ({} resources: {})",
            resources.size,
            counts,
        )

        val prefetchProvider =
            SidecarPrefetchRetrieveProvider(resources, modelResolver, profileRetrieveTargets)
        prefetchProvider.terminologyProvider = terminology
        prefetchProvider.setExpandValueSets(true)

        return PriorityRetrieveProvider(prefetchProvider, remote)
    }

    internal fun flattenPrefetchResources(
        fhirContext: FhirContext,
        prefetch: Map<String, JsonElement>?,
    ): List<Any> {
        if (prefetch.isNullOrEmpty()) return emptyList()
        val parser = fhirContext.newJsonParser()
        val out = ArrayList<Any>()
        for ((_, elem) in prefetch) {
            if (elem.toString() == "null") continue
            val parsed =
                runCatching { parser.parseResource(elem.toString()) as? IBaseResource }
                    .getOrNull() ?: continue
            addResources(parsed, out)
        }
        return out
    }

    internal fun resolveBaseFhirType(dataType: String): String? = profileRetrieveTargets[dataType]

    private fun addResources(resource: IBaseResource, out: MutableList<Any>) {
        if (resource is Bundle) {
            for (entry in resource.entry) {
                entry.resource?.let { out.add(it) }
            }
        } else {
            out.add(resource)
        }
    }

    private fun loadProfileRetrieveTargets(): Map<String, String> {
        val xml =
            javaClass.getResourceAsStream("/modelinfo/atriusin-modelinfo-0.1.0.xml")
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

        val fromModelInfo =
            Regex("""name="([^"]+)"[^>]*target="([^"]+)"[^>]*retrievable="true"""")
                .findAll(xml)
                .associate { it.groupValues[1] to it.groupValues[2] }

        val qicore =
            mapOf(
                "QICoreCondition" to "Condition",
                "QICoreEncounter" to "Encounter",
                "QICoreObservation" to "Observation",
                "QICorePatient" to "Patient",
                "QICoreProcedure" to "Procedure",
            )

        return fromModelInfo + qicore
    }
}
