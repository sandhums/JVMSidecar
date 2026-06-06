package com.atrius.sidecar.cr

import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.RequestGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtractRequestGroupTest {

    @Test
    fun acceptsBareRequestGroup() {
        val rg = RequestGroup().apply { id = "rg-1" }
        assertEquals("rg-1", extractRequestGroup(rg).id)
    }

    @Test
    fun extractsContainedRequestGroupFromCarePlan() {
        val rg =
            RequestGroup().apply {
                id = "rg-contained"
                status = RequestGroup.RequestStatus.ACTIVE
                intent = RequestGroup.RequestIntent.PROPOSAL
            }
        val carePlan =
            CarePlan().apply {
                id = "cp-1"
                status = CarePlan.CarePlanStatus.ACTIVE
                intent = CarePlan.CarePlanIntent.PROPOSAL
                addContained(rg)
                addActivity().reference = Reference("#rg-contained")
            }
        assertEquals("rg-contained", extractRequestGroup(carePlan).id)
    }
}
