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

/** CR settings with QI-Core / FHIR namespaces registered for eCQM libraries. */
fun sidecarCrSettings(): CrSettings {
    val evaluationSettings =
        EvaluationSettings.getDefault()
            .addRegisteredNamespace("FHIR", "http://hl7.org/fhir")
            .addRegisteredNamespace("QICore", "http://hl7.org/fhir/us/qicore")
    return CrSettings.getDefault().withEvaluationSettings(evaluationSettings)
}
