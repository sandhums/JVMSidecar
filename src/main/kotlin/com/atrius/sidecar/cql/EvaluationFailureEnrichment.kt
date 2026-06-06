package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.client.interceptor.ThreadLocalCapturingInterceptor
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import org.slf4j.LoggerFactory

private val evaluationLogger = LoggerFactory.getLogger("com.atrius.sidecar.evaluation")

internal const val FHIR_RESPONSE_SNIPPET_MAX_CHARS = 2048

internal fun Throwable.deepestBaseServerResponseException(): BaseServerResponseException? {
    var cur: Throwable? = this
    var found: BaseServerResponseException? = null
    while (cur != null) {
        if (cur is BaseServerResponseException) {
            found = cur
        }
        cur = cur.cause
    }
    return found
}

internal fun Throwable.flattenCauseMessages(limit: Int = 16): List<String> {
    val out = ArrayList<String>()
    var c: Throwable? = this
    var i = 0
    while (c != null && i++ < limit) {
        out.add("${c.javaClass.simpleName}: ${c.message ?: "(no message)"}")
        c = c.cause
    }
    return out
}

/**
 * Best-effort absolute URL: uses interceptor URI when already absolute; otherwise prefixes [clinicalBaseUrl]
 * (trimmed). Terminology-only failures with a relative URI may still display joined with clinical base — correlate with logs.
 */
internal fun resolveFhirRequestUrl(uriRaw: String?, clinicalBaseUrl: String): String? {
    if (uriRaw.isNullOrBlank()) return null
    val u = uriRaw.trim()
    if (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) {
        return u
    }
    val base = clinicalBaseUrl.trimEnd('/')
    if (base.isBlank()) return u
    return if (u.startsWith("/")) "$base$u" else "$base/$u"
}

internal fun captureFhirHttpContext(
    capture: ThreadLocalCapturingInterceptor?,
    clinicalBaseUrl: String,
): Triple<String?, String?, String?> {
    val req = capture?.requestForCurrentThread ?: return Triple(null, null, null)
    val verb = req.httpVerbName?.trim()?.takeIf { it.isNotEmpty() }
    val uriRaw = req.uri?.trim()?.takeIf { it.isNotEmpty() }
    val resolved = resolveFhirRequestUrl(uriRaw, clinicalBaseUrl)
    return Triple(verb, resolved, uriRaw)
}

internal fun evaluationFailedException(
    messagePrefix: String,
    cause: Throwable,
    capture: ThreadLocalCapturingInterceptor?,
    clinicalBaseUrl: String,
): EvaluationFailedException {
    val bse = cause.deepestBaseServerResponseException()
    val (verb, resolvedUrl, _) = captureFhirHttpContext(capture, clinicalBaseUrl)
    val snippet =
        bse?.responseBody
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > FHIR_RESPONSE_SNIPPET_MAX_CHARS) it.take(FHIR_RESPONSE_SNIPPET_MAX_CHARS) + "…" else it }
    val status = bse?.takeIf { it.statusCode > 0 }?.statusCode
    val causes = cause.flattenCauseMessages()
    val detailed = "$messagePrefix ${cause.toDetailedEvaluationMessage()}"

    evaluationLogger.warn(
        "evaluation failure ${cause.javaClass.simpleName} verb=${verb ?: "?"} httpStatus=${status ?: "-"} url=${resolvedUrl ?: "(unknown)"} snippetChars=${snippet?.length ?: 0}",
        cause,
    )

    return EvaluationFailedException(
        message = detailed,
        cause = cause,
        causes = causes,
        fhirHttpVerb = verb,
        fhirRequestUrl = resolvedUrl,
        fhirHttpStatus = status,
        fhirResponseSnippet = snippet,
    )
}
