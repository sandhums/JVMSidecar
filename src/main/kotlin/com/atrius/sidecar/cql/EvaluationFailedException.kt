package com.atrius.sidecar.cql

/** Evaluation failed (ELM resolution or CQL engine). Optional FHIR HTTP context when upstream FHIR caused the failure. */
class EvaluationFailedException(
    message: String,
    cause: Throwable? = null,
    val causes: List<String> = emptyList(),
    val fhirHttpVerb: String? = null,
    val fhirRequestUrl: String? = null,
    val fhirHttpStatus: Int? = null,
    val fhirResponseSnippet: String? = null,
) : RuntimeException(message, cause)
