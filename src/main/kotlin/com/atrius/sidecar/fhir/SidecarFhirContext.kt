package com.atrius.sidecar.fhir

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.context.support.IValidationSupport
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.opencds.cqf.fhir.cr.CrSettings
import org.opencds.cqf.fhir.cql.EvaluationSettings

/**
 * Shared HAPI [FhirContext] for CQF Clinical Reasoning and FHIR REST clients.
 *
 * CQL condition evaluation inside [org.opencds.cqf.fhir.cr.plandefinition.PlanDefinitionProcessor]
 * requires a non-null [IValidationSupport] on the context (structure definitions + in-memory
 * terminology for type validation during expression evaluation).
 */
fun newSidecarFhirContext(): FhirContext {
    val ctx = FhirContext.forR4()
    ctx.validationSupport = buildSidecarValidationSupport(ctx)
    return ctx
}

fun buildSidecarValidationSupport(fhirContext: FhirContext): IValidationSupport =
    ValidationSupportChain(
        DefaultProfileValidationSupport(fhirContext),
        InMemoryTerminologyServerValidationSupport(fhirContext),
    )

/**
 * Process-wide CR settings. [EvaluationSettings] holds ConcurrentHashMap caches for compiled
 * CQL libraries, models, and ValueSets — recreating settings on every `$apply` (the old
 * `getDefault()` pattern) forced full ELM recompile (~seconds) on every request.
 */
private val SHARED_EVALUATION_SETTINGS: EvaluationSettings =
    EvaluationSettings.getDefault()
        .addRegisteredNamespace("FHIR", "http://hl7.org/fhir")
        .addRegisteredNamespace("QICore", "http://hl7.org/fhir/us/qicore")
        .addRegisteredNamespace("AtriusIn", "https://atrius.in/fhir/r4/atrius-in")

private val SHARED_CR_SETTINGS: CrSettings =
    CrSettings.getDefault().withEvaluationSettings(SHARED_EVALUATION_SETTINGS)

/** CR settings with QI-Core / FHIR / AtriusIn namespaces and shared CQL compile caches. */
fun sidecarCrSettings(): CrSettings = SHARED_CR_SETTINGS

/** Clear CQF EvaluationSettings compile caches (library / model / valueset). */
fun clearSidecarEvaluationCaches(): Int {
    val libs = SHARED_EVALUATION_SETTINGS.libraryCache.size
    val models = SHARED_EVALUATION_SETTINGS.modelCache.size
    val vs = SHARED_EVALUATION_SETTINGS.valueSetCache.size
    SHARED_EVALUATION_SETTINGS.libraryCache.clear()
    SHARED_EVALUATION_SETTINGS.modelCache.clear()
    SHARED_EVALUATION_SETTINGS.valueSetCache.clear()
    return libs + models + vs
}
