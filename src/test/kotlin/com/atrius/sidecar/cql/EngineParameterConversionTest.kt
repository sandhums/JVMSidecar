package com.atrius.sidecar.cql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.hl7.elm.r1.IntervalTypeSpecifier
import org.hl7.elm.r1.Library
import org.hl7.elm.r1.NamedTypeSpecifier
import org.hl7.elm.r1.ParameterDef
import org.opencds.cqf.cql.engine.runtime.DateTime
import org.opencds.cqf.cql.engine.runtime.Interval

class EngineParameterConversionTest {

    @Test
    fun convertsMeasurementPeriodLowHighToDateTimeInterval() {
        val json =
            buildJsonObject {
                put("low", JsonPrimitive("2020-01-01"))
                put("high", JsonPrimitive("2025-12-31"))
                put("lowClosed", JsonPrimitive(true))
                put("highClosed", JsonPrimitive(true))
            }
        val library =
            Library().apply {
                parameters =
                    Library.Parameters().apply {
                        def =
                            mutableListOf(
                                ParameterDef().apply {
                                    name = "Measurement Period"
                                    parameterTypeSpecifier =
                                        IntervalTypeSpecifier().apply {
                                            pointType =
                                                NamedTypeSpecifier().apply {
                                                    name =
                                                        javax.xml.namespace.QName(
                                                            "urn:hl7-org:elm-types:r1",
                                                            "DateTime",
                                                        )
                                                }
                                        }
                                },
                            )
                    }
            }

        val converted = convertEngineParameters(library, mapOf("Measurement Period" to json))
        val interval = converted["Measurement Period"]
        assertTrue(interval is Interval, "expected Interval, got ${interval?.javaClass?.name}")
        assertTrue(interval.low is DateTime)
        assertTrue(interval.high is DateTime)
        assertEquals(true, interval.lowClosed)
        assertEquals(true, interval.highClosed)
    }

    @Test
    fun convertsStartEndIntervalWithExplicitType() {
        val json =
            buildJsonObject {
                put("type", JsonPrimitive("Interval<DateTime>"))
                put(
                    "value",
                    buildJsonObject {
                        put("start", JsonPrimitive("@2020-01-01T00:00:00.0"))
                        put("end", JsonPrimitive("@2025-12-31T23:59:59.999"))
                    },
                )
            }
        val converted = convertEngineParameter("Measurement Period", json)
        assertTrue(converted is Interval)
    }

    @Test
    fun convertsBooleanAndDecimalScalars() {
        val converted =
            convertEngineParameters(
                null,
                mapOf(
                    "Active" to JsonPrimitive(true),
                    "Threshold" to JsonPrimitive(12.5),
                ),
            )
        assertEquals(true, converted["Active"])
        assertNotNull(converted["Threshold"])
    }
}
