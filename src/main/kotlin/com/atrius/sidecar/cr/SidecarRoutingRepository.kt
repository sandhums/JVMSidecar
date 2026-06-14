package com.atrius.sidecar.cr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.IQueryParameterType
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.MethodOutcome
import com.google.common.collect.Multimap
import ca.uhn.fhir.util.BundleUtil
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.instance.model.api.IBaseConformance
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.r4.model.Parameters

/**
 * Routes FHIR repository calls across clinical (data), KR (content), and HTS (terminology) bases.
 *
 * CQF [org.opencds.cqf.fhir.utility.repository.ProxyRepository] returns null from
 * [invoke] overloads that take an [IIdType] (e.g. `ValueSet/{id}/$expand`). PlanDefinition `$apply`
 * with CDS prefetch enables in-memory retrieve, which forces ValueSet expansion through that path.
 */
internal class SidecarRoutingRepository(
    private val fhirContext: FhirContext,
    private val data: IRepository,
    private val content: IRepository,
    private val terminology: IRepository,
) : IRepository {

    override fun <T : IBaseResource, I : IIdType> read(
        resourceType: Class<T>,
        id: I,
        headers: MutableMap<String, String>?,
    ): T = repoForType(resourceType.simpleName).read(resourceType, id, headers)

    override fun <T : IBaseResource> create(
        resource: T,
        headers: MutableMap<String, String>?,
    ): MethodOutcome? = null

    override fun <I : IIdType, P : IBaseParameters> patch(
        id: I,
        patchParameters: P,
        headers: MutableMap<String, String>?,
    ): MethodOutcome? = null

    override fun <T : IBaseResource> update(
        resource: T,
        headers: MutableMap<String, String>?,
    ): MethodOutcome? = null

    override fun <T : IBaseResource, I : IIdType> delete(
        resourceType: Class<T>,
        id: I,
        headers: MutableMap<String, String>?,
    ): MethodOutcome? = null

    override fun <B : IBaseBundle, T : IBaseResource> search(
        bundleType: Class<B>,
        resourceType: Class<T>,
        searchParameters: Multimap<String, MutableList<IQueryParameterType>>?,
        headers: MutableMap<String, String>?,
    ): B = repoForType(resourceType.simpleName).search(bundleType, resourceType, searchParameters, headers)

    override fun <B : IBaseBundle> link(
        bundleType: Class<B>,
        url: String,
        headers: MutableMap<String, String>?,
    ): B? =
        sequenceOf(data, content, terminology)
            .mapNotNull { repo ->
                runCatching { repo.link(bundleType, url, headers) }.getOrNull()
            }
            .firstOrNull { bundle ->
                bundle != null && BundleUtil.toListOfResources(fhirContext, bundle).isNotEmpty()
            }

    override fun <C : IBaseConformance> capabilities(
        resourceType: Class<C>,
        headers: MutableMap<String, String>?,
    ): C? = null

    override fun <B : IBaseBundle> transaction(
        transaction: B,
        headers: MutableMap<String, String>?,
    ): B? = null

    override fun <R : IBaseResource, P : IBaseParameters> invoke(
        name: String,
        parameters: P?,
        returnType: Class<R>,
        headers: MutableMap<String, String>?,
    ): R? = null

    override fun <P : IBaseParameters> invoke(
        name: String,
        parameters: P?,
        headers: MutableMap<String, String>?,
    ): MethodOutcome? = null

    override fun <R : IBaseResource, P : IBaseParameters, T : IBaseResource> invoke(
        resourceType: Class<T>,
        name: String,
        parameters: P?,
        returnType: Class<R>,
        headers: MutableMap<String, String>?,
    ): R = repoForType(resourceType.simpleName).invoke(resourceType, name, parametersOrEmpty(parameters), returnType, headers)

    override fun <P : IBaseParameters, T : IBaseResource> invoke(
        resourceType: Class<T>,
        name: String,
        parameters: P?,
        headers: MutableMap<String, String>?,
    ): MethodOutcome? =
        repoForType(resourceType.simpleName).invoke(resourceType, name, parametersOrEmpty(parameters), headers)

    override fun <R : IBaseResource, P : IBaseParameters, I : IIdType> invoke(
        id: I,
        name: String,
        parameters: P?,
        returnType: Class<R>,
        headers: MutableMap<String, String>?,
    ): R = repoForId(id).invoke(id, name, parametersOrEmpty(parameters), returnType, headers)

    /** CQF ValueSet expansion calls `invoke(id, "$expand", null)` — normalize to empty Parameters for REST. */
    override fun <P : IBaseParameters, I : IIdType> invoke(
        id: I,
        name: String,
        parameters: P?,
        headers: MutableMap<String, String>?,
    ): MethodOutcome = repoForId(id).invoke(id, name, parametersOrEmpty(parameters), headers)

    override fun <B : IBaseBundle, P : IBaseParameters> history(
        parameters: P,
        returnType: Class<B>,
        headers: MutableMap<String, String>?,
    ): B? = null

    override fun <B : IBaseBundle, P : IBaseParameters, T : IBaseResource> history(
        resourceType: Class<T>,
        parameters: P,
        returnType: Class<B>,
        headers: MutableMap<String, String>?,
    ): B? = null

    override fun <B : IBaseBundle, P : IBaseParameters, I : IIdType> history(
        id: I,
        parameters: P,
        returnType: Class<B>,
        headers: MutableMap<String, String>?,
    ): B? = null

    override fun fhirContext(): FhirContext = fhirContext

    private fun repoForId(id: IIdType): IRepository = repoForType(id.resourceType)

    @Suppress("UNCHECKED_CAST")
    private fun <P : IBaseParameters> parametersOrEmpty(parameters: P?): P =
        (parameters ?: Parameters()) as P

    private fun repoForType(resourceType: String?): IRepository =
        when (resourceType) {
            in TERMINOLOGY_TYPES -> terminology
            in CONTENT_TYPES -> content
            else -> data
        }

    companion object {
        private val TERMINOLOGY_TYPES = setOf("ValueSet", "CodeSystem", "ConceptMap")
        private val CONTENT_TYPES =
            setOf(
                "Library",
                "Measure",
                "PlanDefinition",
                "StructureDefinition",
                "ActivityDefinition",
                "Questionnaire",
            )
    }
}
