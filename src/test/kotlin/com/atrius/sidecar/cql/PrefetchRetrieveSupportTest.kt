package com.atrius.sidecar.cql

import com.atrius.sidecar.fhir.newSidecarFhirContext
import kotlinx.serialization.json.Json
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Patient
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefetchRetrieveSupportTest {

    private val fhirContext = newSidecarFhirContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val modelResolver = R4FhirModelResolver(fhirContext)

    @Test
    fun flattenPrefetchResources_expands_searchset_bundle() {
        val patient = Patient().apply { id = "p1" }
        val condition = Condition().apply { id = "c1" }
        val bundle =
            Bundle().apply {
                type = Bundle.BundleType.SEARCHSET
                addEntry().resource = condition
            }
        val prefetch =
            mapOf(
                "patient" to json.parseToJsonElement(fhirContext.newJsonParser().encodeResourceToString(patient)),
                "conditions" to json.parseToJsonElement(fhirContext.newJsonParser().encodeResourceToString(bundle)),
            )

        val flat = PrefetchRetrieveSupport.flattenPrefetchResources(fhirContext, prefetch)

        assertEquals(2, flat.size)
        assertTrue(flat.any { it is Patient })
        assertTrue(flat.any { it is Condition })
    }

    @Test
    fun flattenPrefetchResources_empty_when_null_or_blank() {
        assertTrue(PrefetchRetrieveSupport.flattenPrefetchResources(fhirContext, null).isEmpty())
        assertTrue(PrefetchRetrieveSupport.flattenPrefetchResources(fhirContext, emptyMap()).isEmpty())
    }

    @Test
    fun resolveBaseFhirType_maps_atriusin_profile_retrieves() {
        assertEquals("Condition", PrefetchRetrieveSupport.resolveBaseFhirType("ConditionEncounterDiagnosis"))
        assertEquals("Condition", PrefetchRetrieveSupport.resolveBaseFhirType("ConditionProblemsHealthConcerns"))
        assertEquals("Observation", PrefetchRetrieveSupport.resolveBaseFhirType("ObservationVitalSigns"))
    }

    @Test
    fun sidecar_prefetch_retrieve_aliases_condition_profile_types() {
        val condition =
            Condition().apply {
                id = "c1"
                code =
                    CodeableConcept().addCoding(
                        Coding().setSystem("http://snomed.info/sct").setCode("59621000"),
                    )
            }
        val provider =
            SidecarPrefetchRetrieveProvider(
                listOf(condition),
                modelResolver,
                mapOf("ConditionEncounterDiagnosis" to "Condition"),
            )

        val direct =
            provider.retrieve(
                "Patient",
                "subject",
                "p1",
                "ConditionEncounterDiagnosis",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )
        val rows = direct?.filterNotNull().orEmpty()
        assertTrue(rows.isNotEmpty())
        assertEquals("c1", (rows.first() as Condition).idElement.idPart)
    }

    @Test
    fun sidecar_prefetch_retrieve_filters_encounter_by_type_coding() {
        val encounter =
            Encounter().apply {
                id = "e1"
                addType(
                    CodeableConcept().addCoding(
                        Coding()
                            .setSystem("http://www.ama-assn.org/go/cpt")
                            .setCode("99213"),
                    ),
                )
            }
        val provider = SidecarPrefetchRetrieveProvider(listOf(encounter), modelResolver, emptyMap())
        val filterCodes =
            listOf(
                org.opencds.cqf.cql.engine.runtime.Code()
                    .withSystem("http://www.ama-assn.org/go/cpt")
                    .withCode("99213"),
            )

        val result =
            provider.retrieve(
                "Patient",
                "subject",
                "p1",
                "Encounter",
                null,
                "type",
                filterCodes,
                null,
                null,
                null,
                null,
                null,
            )
        val encounters = result?.filterNotNull().orEmpty()
        assertEquals(1, encounters.size)
        assertEquals("e1", (encounters.first() as Encounter).idElement.idPart)
    }

    @Test
    fun sidecar_prefetch_retrieve_serves_encounter_by_base_type() {
        val encounter = Encounter().apply { id = "e1" }
        val provider = SidecarPrefetchRetrieveProvider(listOf(encounter), modelResolver, emptyMap())

        val result =
            provider.retrieve(
                "Patient",
                "subject",
                "p1",
                "Encounter",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )
        val encounters = result?.filterNotNull().orEmpty()
        assertTrue(encounters.isNotEmpty())
        assertEquals("e1", (encounters.first() as Encounter).idElement.idPart)
    }
}
