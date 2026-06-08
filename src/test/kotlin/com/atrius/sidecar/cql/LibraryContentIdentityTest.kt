package com.atrius.sidecar.cql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Meta

class LibraryContentIdentityTest {

    @Test
    fun prefersMetaVersionId() {
        val lib =
            Library().apply {
                meta = Meta().apply { versionId = "7" }
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+xml"
                        data = "<library/>".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        assertEquals("vid:7", libraryContentIdentity(lib))
    }

    @Test
    fun fallsBackToElmHashWhenVersionIdMissing() {
        val lib =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+xml"
                        data = "<library/>".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        val a = libraryContentIdentity(lib)
        val changed =
            Library().apply {
                addContent(
                    Attachment().apply {
                        contentType = "application/elm+xml"
                        data = "<library id=\"x\"/>".toByteArray(Charsets.UTF_8)
                    },
                )
            }
        val b = libraryContentIdentity(changed)
        assertNotEquals(a, b)
        assertEquals(a, libraryContentIdentity(lib))
    }

    @Test
    fun resourceCacheKeyChangesWhenVersionIdChanges() {
        val logical = libraryLogicalCacheKey(
            org.hl7.elm.r1.VersionedIdentifier().apply {
                id = "CMS165FHIRControllingHighBloodPressure"
                version = "0.3.000"
            },
        )
        val before = libraryResourceCacheKey(logical, "vid:1")
        val after = libraryResourceCacheKey(logical, "vid:2")
        assertNotEquals(before, after)
    }

    @Test
    fun evaluationCacheKeyIncludesPrimaryContentIdentity() {
        val base = "http://127.0.0.1:8079"
        val before =
            EvaluationLibraryCache.cacheKey(
                libraryBase = base,
                libraryId = "Status",
                libraryVersion = "1.15.000",
                primaryContentIdentity = "vid:1",
                includedLibrarySignatures = emptyList(),
            )
        val after =
            EvaluationLibraryCache.cacheKey(
                libraryBase = base,
                libraryId = "Status",
                libraryVersion = "1.15.000",
                primaryContentIdentity = "vid:2",
                includedLibrarySignatures = emptyList(),
            )
        assertNotEquals(before, after)
    }
}
