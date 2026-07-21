package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ApplyPlanDefinitionRequest
import com.atrius.sidecar.api.EvaluateExpressionRequest
import com.atrius.sidecar.cr.SidecarPlanDefinitionApplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryBaseUrlRequirementTest {

    @Test
    fun explicitLibraryBase_returnsTrimmedKrUrl() {
        val request =
            EvaluateExpressionRequest(
                libraryId = "AtriusCommon",
                expression = "Patient",
                hfsBaseUrl = "http://127.0.0.1:8082/",
                htsBaseUrl = "http://127.0.0.1:8090",
                libraryBaseUrl = "http://127.0.0.1:8079/",
                resolveLibraryArtifactsFromFhir = true,
            )
        assertEquals("http://127.0.0.1:8079", explicitLibraryBase(request))
        assertEquals("http://127.0.0.1:8079", requireLibraryBaseForFhirResolution(request))
    }

    @Test
    fun requireLibraryBaseForFhirResolution_rejectsMissingKrBase() {
        val request =
            EvaluateExpressionRequest(
                libraryId = "AtriusCommon",
                expression = "Patient",
                hfsBaseUrl = "http://127.0.0.1:8082",
                htsBaseUrl = "http://127.0.0.1:8090",
                libraryBaseUrl = null,
                resolveLibraryArtifactsFromFhir = true,
            )
        assertNull(explicitLibraryBase(request))
        val ex =
            assertFailsWith<IllegalArgumentException> {
                requireLibraryBaseForFhirResolution(request)
            }
        assertTrue(ex.message!!.contains("libraryBaseUrl is required"))
        assertTrue(ex.message!!.contains("not hfsBaseUrl"))
    }

    @Test
    fun evaluate_withoutLibraryBaseUrl_failsBeforeClinicalFetch() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                SidecarEvaluator().evaluate(
                    EvaluateExpressionRequest(
                        libraryId = "AtriusCommon",
                        expression = "Patient",
                        hfsBaseUrl = "http://127.0.0.1:59999/fhir",
                        htsBaseUrl = "http://127.0.0.1:59998/fhir",
                        resolveLibraryArtifactsFromFhir = true,
                    ),
                )
            }
        assertTrue(ex.message!!.contains("libraryBaseUrl is required"))
    }

    @Test
    fun apply_withoutLibraryBaseUrl_failsFast() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                SidecarPlanDefinitionApplier().apply(
                    ApplyPlanDefinitionRequest(
                        planDefinitionId = "er-chest-pain-pathway",
                        patientId = "demo",
                        hfsBaseUrl = "http://127.0.0.1:59999/fhir",
                        htsBaseUrl = "http://127.0.0.1:59998/fhir",
                        libraryBaseUrl = null,
                    ),
                )
            }
        assertTrue(ex.message!!.contains("libraryBaseUrl is required"))
        assertTrue(ex.message!!.contains("PlanDefinition/\$apply"))
    }

    @Test
    fun requireLibraryBaseForApply_acceptsKr() {
        assertEquals(
            "http://127.0.0.1:8079",
            requireLibraryBaseForApply("http://127.0.0.1:8079/", "PlanDefinition/\$apply"),
        )
    }
}
