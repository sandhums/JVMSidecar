package com.atrius.sidecar.cql

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidecarLibraryCacheAdminTest {
    @Test
    fun clearLibraryCaches_returns_expected_sections() {
        val resp = SidecarLibraryCacheAdmin.clearLibraryCaches()
        assertTrue(resp.cleared.contains("evaluationLibraryStacks"))
        assertTrue(resp.cleared.contains("fhirLibraryResources"))
        assertTrue(resp.cleared.contains("terminologyExpansions"))
    }
}
