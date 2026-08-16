package com.atrius.sidecar.cql

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Meta
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class FhirLibraryElmLoaderTest {

    @AfterEach
    fun clearCaches() {
        FhirLibraryResourceCaches.clearAll()
        EvaluationLibraryCache.clear()
    }

    @Test
    fun loadLibrary_isCacheFirst_secondCallDoesNotFetch() {
        val fetches = AtomicInteger(0)
        val lib = sampleLibrary("AtriusCommon", versionId = "1")
        val loader =
            FhirLibraryElmLoader(
                fetched = mutableMapOf(),
                fetchOverride = { _, _, _ ->
                    fetches.incrementAndGet()
                    lib
                },
            )
        val vid = VersionedIdentifier().apply { id = "AtriusCommon" }
        val first = loader.loadLibrary(vid)
        val second = loader.loadLibrary(vid)
        assertEquals(1, fetches.get())
        assertSame(first, second)
    }

    @Test
    fun loadLibraryFresh_alwaysFetches() {
        val fetches = AtomicInteger(0)
        val loader =
            FhirLibraryElmLoader(
                fetched = mutableMapOf(),
                fetchOverride = { _, _, _ ->
                    fetches.incrementAndGet()
                    sampleLibrary("AtriusCommon", versionId = fetches.get().toString())
                },
            )
        val vid = VersionedIdentifier().apply { id = "AtriusCommon" }
        loader.loadLibraryFresh(vid)
        loader.loadLibraryFresh(vid)
        assertEquals(2, fetches.get())
    }

    @Test
    fun loadLibrary_404_returnsNull() {
        val loader =
            FhirLibraryElmLoader(
                fetched = mutableMapOf(),
                fetchOverride = { _, _, _ -> throw ResourceNotFoundException("Library/missing") },
            )
        assertNull(loader.loadLibrary(VersionedIdentifier().apply { id = "missing" }))
    }

    @Test
    fun loadLibrary_500_propagates() {
        val loader =
            FhirLibraryElmLoader(
                fetched = mutableMapOf(),
                fetchOverride = { _, _, _ -> throw InternalErrorException("KR down") },
            )
        assertThrows(InternalErrorException::class.java) {
            loader.loadLibrary(VersionedIdentifier().apply { id = "AtriusCommon" })
        }
    }

    private fun sampleLibrary(id: String, versionId: String): Library =
        Library().apply {
            this.id = id
            name = id
            version = "0.1.0"
            meta = Meta().apply { this.versionId = versionId }
            addContent(
                Attachment().apply {
                    contentType = "application/elm+xml"
                    data = "<library/>".toByteArray(Charsets.UTF_8)
                },
            )
        }
}
