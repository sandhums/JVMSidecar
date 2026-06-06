package com.atrius.sidecar.cql

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.EncodingEnum
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor
import ca.uhn.fhir.rest.client.interceptor.ThreadLocalCapturingInterceptor
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide FHIR context and generic clients keyed by trimmed base URL.
 * Avoids per-request `GET /metadata` and TCP setup when the same bases are reused.
 */
internal object SidecarFhirClients {
    private val context: FhirContext =
        FhirContext.forR4().also { ctx ->
            ctx.restfulClientFactory.serverValidationMode = ServerValidationModeEnum.ONCE
        }

    private val clients = ConcurrentHashMap<String, IGenericClient>()
    private val captureByBase = ConcurrentHashMap<String, ThreadLocalCapturingInterceptor>()

    fun fhirContext(): FhirContext = context

    fun client(baseUrl: String): IGenericClient {
        val base = baseUrl.trimEnd('/')
        return clients.computeIfAbsent(base) { createClient(base) }
    }

    fun captureForBase(baseUrl: String): ThreadLocalCapturingInterceptor =
        captureByBase.computeIfAbsent(baseUrl.trimEnd('/')) { ThreadLocalCapturingInterceptor() }

    private fun createClient(base: String): IGenericClient =
        context.newRestfulGenericClient(base).apply {
            encoding = EncodingEnum.JSON
            captureByBase[base]?.let { registerInterceptor(it) }
            if (isFhirHttpTraceEnabled()) {
                registerInterceptor(
                    LoggingInterceptor(false).apply {
                        setLogger(LoggerFactory.getLogger("com.atrius.sidecar.fhir.http"))
                        setLogRequestSummary(true)
                        setLogResponseSummary(true)
                        setLogRequestHeaders(true)
                        setLogResponseHeaders(false)
                        setLogRequestBody(false)
                        setLogResponseBody(false)
                    },
                )
            }
        }
}
