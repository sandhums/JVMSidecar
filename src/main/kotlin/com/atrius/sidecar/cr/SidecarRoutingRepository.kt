package com.atrius.sidecar.cr

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.IQueryParameterType
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.MethodOutcome
import ca.uhn.fhir.rest.param.StringParam
import ca.uhn.fhir.util.BundleUtil
import com.atrius.sidecar.cql.normalizeLibraryIdentifier
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Multimap
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.instance.model.api.IBaseConformance
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Parameters

/**
 * Routes FHIR repository calls across clinical (data), KR (content), and HTS (terminology) bases.
 *
 * **Library / PlanDefinition / ActivityDefinition** always go to the **content** (KR) repository —
 * including CQL `include` resolution via CQF [org.opencds.cqf.fhir.cql.cql2elm.content.RepositoryFhirLibrarySourceProvider].
 * Clinical [data] is never used for knowledge artifacts.
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
    /** KR base URL — used as content-cache namespace (empty disables content caching). */
    private val contentBaseUrl: String = "",
    /** HTS base URL — used as expand-cache namespace (empty disables expand caching). */
    private val terminologyBaseUrl: String = "",
) : IRepository {

    override fun <T : IBaseResource, I : IIdType> read(
        resourceType: Class<T>,
        id: I,
        headers: MutableMap<String, String>?,
    ): T {
        val typeName = resourceType.simpleName
        @Suppress("UNCHECKED_CAST")
        val effectiveId = (if (typeName == "Library") normalizeLibraryReadId(id) else id) as I
        if (contentBaseUrl.isNotBlank() && typeName in CONTENT_TYPES) {
            val idPart = effectiveId.idPart?.takeIf { it.isNotBlank() }
            if (idPart != null) {
                val key = SidecarKrContentCache.cacheKey(contentBaseUrl, typeName, idPart)
                return SidecarKrContentCache.getOrLoad(key) {
                    content.read(resourceType, effectiveId, headers)
                }
            }
        }
        return repoForType(typeName).read(resourceType, effectiveId, headers)
    }

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
    ): B {
        val typeName = resourceType.simpleName
        val params =
            if (typeName == "Library") {
                normalizeLibraryNameSearchParams(searchParameters)
            } else {
                searchParameters
            }
        return repoForType(typeName).search(bundleType, resourceType, params, headers)
    }

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
    ): R {
        if (terminologyBaseUrl.isNotBlank() &&
            resourceType.simpleName == "ValueSet" &&
            isExpandOp(name)
        ) {
            val key =
                SidecarExpandCache.cacheKey(
                    terminologyBaseUrl,
                    "ValueSet",
                    expandUrlCacheKey(parameters),
                    name,
                )
            return SidecarExpandCache.getOrLoad(key) {
                terminology.invoke(
                    resourceType,
                    name,
                    parametersOrEmpty(parameters),
                    returnType,
                    headers,
                )
            }
        }
        return repoForType(resourceType.simpleName)
            .invoke(resourceType, name, parametersOrEmpty(parameters), returnType, headers)
    }

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
    ): R {
        if (terminologyBaseUrl.isNotBlank() && isExpandOp(name)) {
            val key =
                SidecarExpandCache.cacheKey(
                    terminologyBaseUrl,
                    id.resourceType,
                    id.idPart,
                    name,
                )
            return SidecarExpandCache.getOrLoad(key) {
                repoForId(id).invoke(id, name, parametersOrEmpty(parameters), returnType, headers)
            }
        }
        return repoForId(id).invoke(id, name, parametersOrEmpty(parameters), returnType, headers)
    }

    /** CQF ValueSet expansion calls `invoke(id, "$expand", null)` — normalize to empty Parameters for REST. */
    override fun <P : IBaseParameters, I : IIdType> invoke(
        id: I,
        name: String,
        parameters: P?,
        headers: MutableMap<String, String>?,
    ): MethodOutcome {
        if (terminologyBaseUrl.isNotBlank() && isExpandOp(name)) {
            val key =
                SidecarExpandCache.cacheKey(
                    terminologyBaseUrl,
                    id.resourceType,
                    id.idPart,
                    "$name:outcome",
                )
            return SidecarExpandCache.getOrLoad(key) {
                repoForId(id).invoke(id, name, parametersOrEmpty(parameters), headers)
            }
        }
        return repoForId(id).invoke(id, name, parametersOrEmpty(parameters), headers)
    }

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

        private fun isExpandOp(name: String): Boolean =
            name == "\$expand" || name.equals("expand", ignoreCase = true)

        private fun expandUrlCacheKey(parameters: IBaseParameters?): String {
            if (parameters !is Parameters) return "noparams"
            val url =
                parameters.parameter
                    ?.firstOrNull { it.name == "url" }
                    ?.value
                    ?.primitiveValue()
                    ?.trim()
                    .orEmpty()
            return url.ifBlank { "noparams" }
        }

        /** Map canonical / URL library ids to KR logical ids before content read. */
        internal fun normalizeLibraryReadId(id: IIdType): IIdType {
            val part = id.idPart?.takeIf { it.isNotBlank() } ?: return id
            val logical =
                normalizeLibraryIdentifier(
                    VersionedIdentifier().apply { this.id = part },
                ).id ?: return id
            if (logical == part) return id
            return IdType("Library", logical)
        }

        /**
         * CQF [RepositoryFhirLibrarySourceProvider] searches `Library?name={include.path}`.
         * When include.path is an Atrius canonical URL, rewrite to the KR logical name.
         */
        internal fun normalizeLibraryNameSearchParams(
            searchParameters: Multimap<String, MutableList<IQueryParameterType>>?,
        ): Multimap<String, MutableList<IQueryParameterType>>? {
            if (searchParameters == null) return null
            var changed = false
            val out = ArrayListMultimap.create<String, MutableList<IQueryParameterType>>()
            for (key in searchParameters.keySet()) {
                for (orList in searchParameters.get(key)) {
                    if (key != "name") {
                        out.put(key, orList)
                        continue
                    }
                    val rewritten = ArrayList<IQueryParameterType>(orList.size)
                    for (param in orList) {
                        if (param is StringParam) {
                            val raw = param.value.orEmpty()
                            val logical =
                                normalizeLibraryIdentifier(
                                    VersionedIdentifier().apply { id = raw },
                                ).id ?: raw
                            if (logical != raw) {
                                changed = true
                                rewritten.add(StringParam(logical))
                            } else {
                                rewritten.add(param)
                            }
                        } else {
                            rewritten.add(param)
                        }
                    }
                    out.put(key, rewritten)
                }
            }
            return if (changed) out else searchParameters
        }
    }
}
