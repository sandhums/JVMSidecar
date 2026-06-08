package com.atrius.sidecar.cr

import com.atrius.sidecar.cql.PrefetchRetrieveSupport
import com.atrius.sidecar.fhir.newSidecarFhirContext
import kotlinx.serialization.json.Json
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.fhir.utility.repository.InMemoryFhirRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: nested searchset Bundles in CDS prefetch must be flattened before
 * [InMemoryFhirRepository] construction (duplicate null Bundle ids).
 */
class SidecarPlanDefinitionApplierPrefetchTest {

    private val fhirContext = newSidecarFhirContext()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun flattenPrefetch_supports_in_memory_repository_construction() {
        val patient = Patient().apply { id = "cms165-demo" }
        val condition = Condition().apply { id = "c1" }
        val encounter = Encounter().apply { id = "e1" }
        val conditionsBundle =
            Bundle().apply {
                type = Bundle.BundleType.SEARCHSET
                addEntry().resource = condition
            }
        val encountersBundle =
            Bundle().apply {
                type = Bundle.BundleType.SEARCHSET
                addEntry().resource = encounter
            }
        val prefetch =
            mapOf(
                "patient" to json.parseToJsonElement(fhirContext.newJsonParser().encodeResourceToString(patient)),
                "conditions" to
                    json.parseToJsonElement(
                        fhirContext.newJsonParser().encodeResourceToString(conditionsBundle),
                    ),
                "encounters" to
                    json.parseToJsonElement(
                        fhirContext.newJsonParser().encodeResourceToString(encountersBundle),
                    ),
            )

        val flat = PrefetchRetrieveSupport.flattenPrefetchResources(fhirContext, prefetch)
        assertEquals(3, flat.size)

        val dataBundle = Bundle().apply { type = Bundle.BundleType.COLLECTION }
        for (resource in flat) {
            if (resource is Resource) {
                dataBundle.addEntry().resource = resource
            }
        }

        val repo = InMemoryFhirRepository(fhirContext, dataBundle)
        assertNotNull(repo.read(Patient::class.java, patient.idElement, emptyMap()))
        assertNotNull(repo.read(Condition::class.java, condition.idElement, emptyMap()))
        assertNotNull(repo.read(Encounter::class.java, encounter.idElement, emptyMap()))
        assertTrue(flat.none { it is Bundle })
    }
}
