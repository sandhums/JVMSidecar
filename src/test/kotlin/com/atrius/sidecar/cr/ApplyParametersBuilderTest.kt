package com.atrius.sidecar.cr

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.hl7.fhir.r4.model.Parameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ApplyParametersBuilderTest {

    @Test
    fun defaultsMeasurementPeriodToCurrentYearWhenAbsent() {
        val params = buildApplyParameters(null) as Parameters
        val period = params.parameter[0].value as org.hl7.fhir.r4.model.Period
        assertEquals("Measurement Period", params.parameter[0].name)
        assertNotNull(period.startElement?.valueAsString)
        assertNotNull(period.endElement?.valueAsString)
    }

    @Test
    fun preservesExplicitMeasurementPeriod() {
        val params =
            buildApplyParameters(
                mapOf(
                    "Measurement Period" to
                        buildJsonObject {
                            put("low", JsonPrimitive("2020-01-01T00:00:00.000+00:00"))
                            put("high", JsonPrimitive("2025-12-31T23:59:59.999+00:00"))
                        },
                ),
            ) as Parameters
        val period = params.parameter[0].value as org.hl7.fhir.r4.model.Period
        assertEquals("2020-01-01T00:00:00.000+00:00", period.startElement.valueAsString)
        assertEquals("2025-12-31T23:59:59.999+00:00", period.endElement.valueAsString)
    }
}
