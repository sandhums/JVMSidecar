package com.atrius.sidecar.cql

import java.net.HttpURLConnection
import java.net.URI
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Demo patient seeded by `atrius-hfs/scripts/import-cms165-demo.py`. */
internal const val DEFAULT_CMS165_DEMO_PATIENT = "cms165-demo"

/**
 * Integration tests that call a live FHIR base (bridge, HFS, HTS, KR) skip when the endpoint
 * is unreachable so `mvn package` succeeds without the full Helios stack running.
 */
internal fun assumeFhirMetadataReachable(baseUrl: String, label: String = baseUrl) {
    val code = httpGetStatusCode(baseUrl.trimEnd('/') + "/metadata")
    assumeTrue(
        code == 200,
        "$label FHIR metadata not reachable (HTTP $code). " +
            "Start cr-fhir-bridge (8081) or clinical HFS (8082), or set HFS_BRIDGE_URL.",
    )
}

internal fun resolveHfsBridgeUrlForTests(): String =
    System.getenv("HFS_BRIDGE_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8081"

internal fun resolveKrBaseUrlForTests(): String =
    System.getenv("KR_BASE_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8079"

internal fun resolveHtsBaseUrlForTests(): String =
    System.getenv("HTS_BASE_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:9091"

internal fun resolveDemoPatientIdForTests(): String =
    System.getenv("TEST_PATIENT_ID")?.takeIf { it.isNotBlank() } ?: DEFAULT_CMS165_DEMO_PATIENT

/** Skip unless the demo patient and clinical search path work (not just `/metadata`). */
internal fun assumeCms165ClinicalDataReady(
    bridge: String = resolveHfsBridgeUrlForTests(),
    patientId: String = resolveDemoPatientIdForTests(),
) {
    assumeFhirMetadataReachable(bridge, "HFS bridge")
    val patientCode = httpGetStatusCode("${bridge.trimEnd('/')}/Patient/$patientId")
    assumeTrue(
        patientCode == 200,
        "Patient/$patientId not readable via bridge (HTTP $patientCode). " +
            "Run ./scripts/import-cms165-demo.py or set TEST_PATIENT_ID.",
    )
    val encounterSearchCode =
        httpGetStatusCode(
            "${bridge.trimEnd('/')}/Encounter?patient=$patientId&_count=1",
        )
    assumeTrue(
        encounterSearchCode == 200,
        "Encounter search via bridge returned HTTP $encounterSearchCode (expected FHIR search Bundle). " +
            "Confirm cr-fhir-bridge upstream is clinical HFS (8082), not a non-FHIR process on 8081.",
    )
}

internal fun assumeKrLibraryPresent(
    kr: String = resolveKrBaseUrlForTests(),
    libraryId: String,
) {
    assumeFhirMetadataReachable(kr, "KR")
    val code = httpGetStatusCode("${kr.trimEnd('/')}/Library/$libraryId")
    assumeTrue(
        code == 200,
        "Library/$libraryId not on KR (HTTP $code). Import eCQM/Atrius libraries to KR.",
    )
}

internal fun assumeCms165EvaluationStackReady(
    libraryId: String,
    bridge: String = resolveHfsBridgeUrlForTests(),
    kr: String = resolveKrBaseUrlForTests(),
    hts: String = resolveHtsBaseUrlForTests(),
    patientId: String = resolveDemoPatientIdForTests(),
) {
    assumeCms165ClinicalDataReady(bridge, patientId)
    assumeKrLibraryPresent(kr, libraryId)
    assumeFhirMetadataReachable(hts, "HTS")
}

internal fun httpGetStatusCode(url: String): Int =
    runCatching {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/fhir+json")
        if (conn.responseCode >= 400) {
            conn.errorStream?.use { }
        } else {
            conn.inputStream.use { }
        }
        conn.responseCode
    }.getOrElse { -1 }

/** @deprecated use [httpGetStatusCode] */
internal fun fhirMetadataStatusCode(baseUrl: String): Int = httpGetStatusCode(baseUrl.trimEnd('/') + "/metadata")
