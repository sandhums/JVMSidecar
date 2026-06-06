package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException

/**
 * Builds a single message that includes the cause chain. When HAPI's REST client throws
 * [BaseServerResponseException], includes HTTP status and response body (often an OperationOutcome)
 * so API callers can diagnose FHIR server failures instead of seeing only "HTTP 500".
 */
internal fun Throwable.toDetailedEvaluationMessage(): String =
    buildString {
        var t: Throwable? = this@toDetailedEvaluationMessage
        var depth = 0
        while (t != null && depth < 12) {
            if (depth > 0) {
                append(" Caused by: ")
            }
            append(t.javaClass.simpleName).append(": ").append(t.message ?: "(no message)")
            if (t is BaseServerResponseException) {
                append(" [HTTP ").append(t.statusCode).append(']')
                val body = t.responseBody?.trim()?.takeIf { it.isNotEmpty() }
                if (body != null) {
                    append(" Response: ")
                    append(if (body.length > 2048) body.take(2048) + "…" else body)
                }
            }
            t = t.cause
            depth++
        }
    }
