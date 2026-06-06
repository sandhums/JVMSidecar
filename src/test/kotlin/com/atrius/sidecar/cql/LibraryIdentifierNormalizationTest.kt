package com.atrius.sidecar.cql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hl7.elm.r1.VersionedIdentifier

class LibraryIdentifierNormalizationTest {

    @Test
    fun detectsAtriusCanonicalUrl() {
        val id =
            VersionedIdentifier().apply {
                id = "https://atrius.in/fhir/r4/atrius-in/AtriusCommon"
                version = "0.1.0"
            }
        assertTrue(isAtriusCanonicalLibraryIdentifier(id))
        val normalized = normalizeLibraryIdentifier(id)
        assertEquals("AtriusCommon", normalized.id)
        assertEquals("0.1.0", normalized.version)
    }

    @Test
    fun leavesLogicalKrIdUnchanged() {
        val id =
            VersionedIdentifier().apply {
                id = "AtriusCommon"
                version = "0.1.0"
            }
        assertFalse(isAtriusCanonicalLibraryIdentifier(id))
        assertEquals("AtriusCommon", normalizeLibraryIdentifier(id).id)
    }
}
