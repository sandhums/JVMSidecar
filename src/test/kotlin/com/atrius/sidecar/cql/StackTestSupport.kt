package com.atrius.sidecar.cql

import java.net.HttpURLConnection
import java.net.URI
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Integration tests that call a live FHIR base (bridge, HFS, HTS, KR) skip when the endpoint
 * is unreachable so `mvn package` succeeds without the full Helios stack running.
 */
internal fun assumeFhirMetadataReachable(baseUrl: String, label: String = baseUrl) {
    val code = fhirMetadataStatusCode(baseUrl)
    assumeTrue(
        code == 200,
        "$label FHIR metadata not reachable (HTTP $code). " +
            "Start cr-fhir-bridge (8081) or clinical HFS (8082), or set HFS_BRIDGE_URL.",
    )
}

internal fun resolveHfsBridgeUrlForTests(): String =
    System.getenv("HFS_BRIDGE_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8081"

internal fun fhirMetadataStatusCode(baseUrl: String): Int {
    val url = baseUrl.trimEnd('/') + "/metadata"
    return runCatching {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        conn.requestMethod = "GET"
        conn.inputStream.use { }
        conn.responseCode
    }.getOrElse { -1 }
}
