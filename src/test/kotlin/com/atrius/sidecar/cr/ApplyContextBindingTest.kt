package com.atrius.sidecar.cr

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApplyContextBindingTest {

    @Test
    fun normalizeApplyReferencePassesThroughFullReference() {
        assertEquals(
            "PractitionerRole/abc",
            normalizeApplyReference("PractitionerRole/abc", "Practitioner"),
        )
    }

    @Test
    fun normalizeApplyReferencePrefixesBareId() {
        assertEquals("Encounter/enc-1", normalizeApplyReference("enc-1", "Encounter"))
    }

    @Test
    fun parseCodeableConceptFromJson() {
        val cc =
            parseCodeableConcept(
                buildJsonObject {
                    put("text", JsonPrimitive("inpatient"))
                },
            )
        assertNotNull(cc)
        assertEquals("inpatient", cc!!.text)
    }

    @Test
    fun parseCodeableConceptNullForMissing() {
        assertNull(parseCodeableConcept(null))
    }
}
