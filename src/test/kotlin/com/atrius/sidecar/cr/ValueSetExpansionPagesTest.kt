package com.atrius.sidecar.cr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.ValueSet

class ValueSetExpansionPagesTest {

    @Test
    fun parametersOmitExpandCount_isTrueWhenCountAbsent() {
        assertTrue(parametersOmitExpandCount(null))
        assertTrue(parametersOmitExpandCount(Parameters()))
        val counted =
            Parameters().apply {
                addParameter().setName("count").value = IntegerType(20)
            }
        assertTrue(!parametersOmitExpandCount(counted))
    }

    @Test
    fun completePartialExpansion_reloadsFromOffsetZero() {
        val first = expansion(total = 5, codes = listOf("snomed-only"))
        val completed =
            completePartialExpansion(first) { offset, count ->
                assertEquals(0, offset)
                assertEquals(EXPANSION_PAGE_SIZE, count)
                expansion(total = 5, codes = listOf("I63", "I63.0", "I63.8", "I63.9", "I64"))
            }
        assertEquals(listOf("I63", "I63.0", "I63.8", "I63.9", "I64"), codesOf(completed))
    }

    @Test
    fun completePartialExpansion_walksLaterOffsetPages() {
        val first = expansion(total = 4, codes = listOf("not-a-prefix"))
        val completed =
            completePartialExpansion(first, pageSize = 2) { offset, _ ->
                when (offset) {
                    0 -> expansion(total = 4, codes = listOf("I63", "I63.0"))
                    2 -> expansion(total = 4, codes = listOf("I63.9", "I64"))
                    else -> expansion(total = 4, codes = emptyList())
                }
            }
        assertEquals(listOf("I63", "I63.0", "I63.9", "I64"), codesOf(completed))
    }

    @Test
    fun completePartialExpansion_leavesAFullPageAlone() {
        val first = expansion(total = 2, codes = listOf("I63.9", "I64"))
        var loads = 0
        val completed =
            completePartialExpansion(first) { _, _ ->
                loads++
                expansion(total = 2, codes = listOf("should-not-load"))
            }
        assertEquals(0, loads)
        assertEquals(listOf("I63.9", "I64"), codesOf(completed))
    }

    @Test
    fun completePartialExpansion_stopsWhenAPageIsEmpty() {
        val first = expansion(total = 10, codes = listOf("A"))
        val completed =
            completePartialExpansion(first, pageSize = 2) { _, _ ->
                expansion(total = 10, codes = emptyList())
            }
        assertEquals(listOf("A"), codesOf(completed))
    }

    private fun expansion(total: Int, codes: List<String>): ValueSet =
        ValueSet().apply {
            expansion =
                ValueSet.ValueSetExpansionComponent().apply {
                    this.total = total
                    codes.forEach { code -> addContains().setCode(code) }
                }
        }

    private fun codesOf(valueSet: ValueSet): List<String> =
        valueSet.expansion.contains.map { it.code }
}
