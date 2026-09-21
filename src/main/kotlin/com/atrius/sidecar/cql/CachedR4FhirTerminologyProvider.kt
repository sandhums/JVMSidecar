package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.client.api.IGenericClient
import org.opencds.cqf.cql.engine.fhir.terminology.R4FhirTerminologyProvider
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.terminology.CodeSystemInfo
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo

/**
 * [R4FhirTerminologyProvider] with process-wide caching of ValueSet `$expand` results.
 *
 * CQL evaluation often expands the same ValueSet many times per request (and again across
 * requests). Each uncached expand triggers `ValueSet?url=…` resolution plus `$expand` on HTS.
 */
internal class CachedR4FhirTerminologyProvider(
    fhirClient: IGenericClient,
    htsBaseUrl: String,
) : TerminologyProvider {

    private val delegate = R4FhirTerminologyProvider(fhirClient)
    private val htsBase = htsBaseUrl.trimEnd('/')

    // Later: do not forward to CQF $validate-code. HTS puts Parameters[0]=code
    // (CodeType); CQF casts it to BooleanType and throws. Measure eval then
    // reports not-in. Implement via expand cache (system+code) or read the
    // `result` parameter by name. See docs/how-it-works.md § Later: CQL `in`.
    override fun `in`(code: Code, valueSet: ValueSetInfo): Boolean = delegate.`in`(code, valueSet)

    override fun lookup(code: Code, codeSystem: CodeSystemInfo): Code = delegate.lookup(code, codeSystem)

    override fun expand(valueSet: ValueSetInfo): Iterable<Code> =
        ValueSetExpansionCache.getOrExpand(htsBase, expansionCacheKey(valueSet)) {
            delegate.expand(valueSet).map { copyCode(it) }
        }

    private fun expansionCacheKey(valueSet: ValueSetInfo): String {
        val id = valueSet.id.orEmpty()
        val version = valueSet.version.orEmpty()
        return "$id\u0000$version"
    }

    private fun copyCode(code: Code): Code =
        Code()
            .withSystem(code.system)
            .withCode(code.code)
            .withVersion(code.version)
            .withDisplay(code.display)
}

/** ValueSet expansion codes keyed by `(htsBase, valueSetId, version)`. */
internal object ValueSetExpansionCache {
    private val byKey = SidecarProcessCaches.contentCache<String, List<Code>>()

    /**
     * Cache non-empty expansions only. An empty `$expand` is usually a transient HTS/KR miss;
     * caching it poisons later CQL retrieves (e.g. ObservationVitalSigns category filters →
     * sepsis SIRS criteria silently false until admin cache clear).
     */
    fun getOrExpand(
        htsBase: String,
        valueSetKey: String,
        loader: () -> List<Code>,
    ): List<Code> {
        val key = "${htsBase.trimEnd('/')}\u0000$valueSetKey"
        byKey.getIfPresent(key)?.let { return it }
        val loaded = loader()
        if (loaded.isNotEmpty()) {
            byKey.put(key, loaded)
        }
        return loaded
    }

    fun clear(): Int = SidecarProcessCaches.invalidateAll(byKey)
}
