package com.atrius.sidecar.cr

import com.atrius.sidecar.api.EvaluateMeasureRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.ZoneOffset

class SidecarMeasureEvaluatorTest {

    @Test
    fun evaluate_requiresMeasureIdOrUrl() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                SidecarMeasureEvaluator().evaluate(
                    EvaluateMeasureRequest(
                        patientId = "demo",
                        hfsBaseUrl = "http://127.0.0.1:59999/fhir",
                        htsBaseUrl = "http://127.0.0.1:59998/fhir",
                        libraryBaseUrl = "http://127.0.0.1:59997/fhir",
                    ),
                )
            }
        assertTrue(ex.message!!.contains("measureId or measureUrl"))
    }

    @Test
    fun evaluate_requiresLibraryBaseUrl() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                SidecarMeasureEvaluator().evaluate(
                    EvaluateMeasureRequest(
                        measureId = "CMS165",
                        patientId = "demo",
                        hfsBaseUrl = "http://127.0.0.1:59999/fhir",
                        htsBaseUrl = "http://127.0.0.1:59998/fhir",
                        libraryBaseUrl = null,
                    ),
                )
            }
        assertTrue(ex.message!!.contains("libraryBaseUrl is required"))
    }

    @Test
    fun parseMeasureInstant_acceptsDateAndInstant() {
        val start = parseMeasureInstant("2026-01-01", endOfDay = false)!!
        assertEquals(2026, start.year)
        assertEquals(ZoneOffset.UTC, start.offset)
        val end = parseMeasureInstant("2026-12-31", endOfDay = true)!!
        assertEquals(23, end.hour)
        val zoned = parseMeasureInstant("2026-01-01T00:00:00Z", endOfDay = false)!!
        assertEquals(2026, zoned.year)
    }
}
