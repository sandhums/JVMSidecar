package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.client.api.IGenericClient
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.StringType
import org.opencds.cqf.cql.engine.exception.TerminologyProviderException
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

    /**
     * Membership via HTS `ValueSet/$validate-code`, reading the `result` parameter by name.
     *
     * CQF [R4FhirTerminologyProvider.in] casts `Parameters[0]` to [BooleanType]. HTS echoes
     * `code` first and puts `result` later, so that cast throws and measure evaluation
     * reports not-in. Codes with no system still use the delegate, which expands.
     */
    override fun `in`(code: Code, valueSet: ValueSetInfo): Boolean {
        if (code.system.isNullOrBlank()) {
            return delegate.`in`(code, valueSet)
        }
        try {
            val id = delegate.resolveValueSetId(valueSet)
            val response =
                delegate.fhirClient
                    .operation()
                    .onInstance(IdType("ValueSet", id))
                    .named("validate-code")
                    .withParameter(Parameters::class.java, "code", StringType(code.code))
                    .andParameter("system", StringType(code.system))
                    .useHttpGet()
                    .execute()
            return validateCodeResult(response)
        } catch (e: TerminologyProviderException) {
            throw e
        } catch (e: Exception) {
            throw TerminologyProviderException(
                "Error performing membership check of Code: $code in ValueSet: ${valueSet.id}",
                e,
            )
        }
    }

    private fun validateCodeResult(response: Parameters): Boolean {
        val value = response.getParameter("result")?.value
        if (value is BooleanType) {
            return value.booleanValue()
        }
        throw TerminologyProviderException(
            "ValueSet/\$validate-code response has no boolean result parameter",
        )
    }

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
