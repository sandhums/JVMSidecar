package com.atrius.sidecar.cql

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.EncodingEnum
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.engine.exception.TerminologyProviderException
import org.opencds.cqf.cql.engine.fhir.terminology.R4FhirTerminologyProvider
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * CMS131 autonomous eye-exam membership against an HTS-shaped `$validate-code` response.
 *
 * HTS returns `code` first and `result` later. CQF casts parameter 0 to boolean, throws,
 * and measure evaluation reports not-in. `LA34398-0` is in
 * `atrius-vs-autonomous-eye-exam-result`.
 */
class Cms131ValueSetInTest {

    @Test
    fun cms131_etdrs_code_is_in_valueset_when_hts_puts_code_before_result() {
        hts().use { hts ->
            val code = etdrsLevel20()
            val valueSet = autonomousEyeExamValueSet()

            val cqf = R4FhirTerminologyProvider(hts.client())
            val thrown =
                org.junit.jupiter.api.Assertions.assertThrows(TerminologyProviderException::class.java) {
                    cqf.`in`(code, valueSet)
                }
            assertInstanceOf(ClassCastException::class.java, thrown.cause)

            val provider = CachedR4FhirTerminologyProvider(hts.client(), hts.baseUrl)
            assertTrue(provider.`in`(code, valueSet))
            assertEquals("LA34398-0", hts.lastValidatedCode)
        }
    }

    private fun etdrsLevel20(): Code =
        Code()
            .withSystem(LOINC_ANSWERS)
            .withCode("LA34398-0")
            .withDisplay("ETDRS Level 20 or lower, without macular edema")

    private fun autonomousEyeExamValueSet(): ValueSetInfo =
        ValueSetInfo().withId(AUTONOMOUS_EYE_EXAM_VS)

    private fun hts(): HtsParameterOrderServer = HtsParameterOrderServer()

    private class HtsParameterOrderServer : AutoCloseable {
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
                http.createContext("/") { exchange ->
                    val path = exchange.requestURI.path.orEmpty()
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    val json =
                        when {
                            path.contains("\$validate-code") -> validateCode(query)
                            path.contains("ValueSet") -> valueSetSearch()
                            else -> null
                        }
                    if (json == null) {
                        exchange.sendResponseHeaders(404, -1)
                        exchange.close()
                        return@createContext
                    }
                    val bytes = json.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/fhir+json; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                http.start()
            }

        @Volatile var lastValidatedCode: String? = null

        val baseUrl: String = "http://127.0.0.1:${server.address.port}/fhir"

        fun client() =
            FhirContext.forR4().apply {
                restfulClientFactory.serverValidationMode = ServerValidationModeEnum.NEVER
                restfulClientFactory.connectTimeout = 5_000
                restfulClientFactory.socketTimeout = 5_000
            }.newRestfulGenericClient(baseUrl).apply {
                encoding = EncodingEnum.JSON
            }

        override fun close() {
            server.stop(0)
        }

        private fun valueSetSearch(): String =
            """
            {
              "resourceType": "Bundle",
              "type": "searchset",
              "total": 1,
              "entry": [{
                "resource": {
                  "resourceType": "ValueSet",
                  "id": "atrius-vs-autonomous-eye-exam-result",
                  "url": "$AUTONOMOUS_EYE_EXAM_VS",
                  "status": "active"
                }
              }]
            }
            """.trimIndent()

        private fun validateCode(rawQuery: String): String {
            val params = queryParams(rawQuery)
            val code = params["code"].orEmpty()
            lastValidatedCode = code
            val member = code == "LA34398-0"
            return """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    {"name": "code", "valueCode": ${jsonString(code)}},
                    {"name": "system", "valueUri": ${jsonString(params["system"].orEmpty())}},
                    {"name": "result", "valueBoolean": $member}
                  ]
                }
                """.trimIndent()
        }
    }

    private companion object {
        const val LOINC_ANSWERS =
            "https://atrius.in/fhir/r4/atrius-in/CodeSystem/atrius-in-loinc-answers"
        const val AUTONOMOUS_EYE_EXAM_VS =
            "https://atrius.in/fhir/r4/atrius-in/ValueSet/atrius-vs-autonomous-eye-exam-result"

        fun queryParams(rawQuery: String): Map<String, String> =
            rawQuery.split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) {
                    null
                } else {
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8) to
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                }
            }.toMap()

        fun jsonString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
