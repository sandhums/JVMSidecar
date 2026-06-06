package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.client.api.IGenericClient
import org.opencds.cqf.cql.engine.fhir.terminology.R4FhirTerminologyProvider
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.terminology.CodeSystemInfo
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo
import java.util.concurrent.ConcurrentHashMap

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
    private val byHtsBase = ConcurrentHashMap<String, ConcurrentHashMap<String, List<Code>>>()

    fun getOrExpand(
        htsBase: String,
        valueSetKey: String,
        loader: () -> List<Code>,
    ): List<Code> {
        val bucket = byHtsBase.computeIfAbsent(htsBase) { ConcurrentHashMap() }
        return bucket.computeIfAbsent(valueSetKey) { loader() }
    }

    fun clear(): Int {
        val n = byHtsBase.size
        byHtsBase.clear()
        return n
    }
}
