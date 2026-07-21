package com.atrius.sidecar.cr

import ca.uhn.fhir.model.api.IQueryParameterType
import ca.uhn.fhir.rest.param.StringParam
import com.google.common.collect.ArrayListMultimap
import com.atrius.sidecar.fhir.newSidecarFhirContext
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Library
import org.opencds.cqf.fhir.utility.repository.InMemoryFhirRepository

class SidecarRoutingRepositoryLibraryTest {

    private val fhirContext = newSidecarFhirContext()

    @Test
    fun normalizeLibraryReadId_mapsCanonicalToLogicalId() {
        val raw = IdType("Library", "https://atrius.in/fhir/r4/atrius-in/AtriusCommon")
        val normalized = SidecarRoutingRepository.normalizeLibraryReadId(raw)
        assertEquals("AtriusCommon", normalized.idPart)
    }

    @Test
    fun normalizeLibraryNameSearchParams_rewritesCanonicalName() {
        val params = ArrayListMultimap.create<String, MutableList<IQueryParameterType>>()
        params.put("name", mutableListOf(StringParam("https://atrius.in/fhir/r4/atrius-in/AtriusCommon")))
        val rewritten = SidecarRoutingRepository.normalizeLibraryNameSearchParams(params)!!
        val nameParam = rewritten.get("name").first().first() as StringParam
        assertEquals("AtriusCommon", nameParam.value)
    }

    @Test
    fun libraryRead_routesToContentNotData() {
        val contentLib =
            Library().apply {
                id = "AtriusCommon"
                name = "AtriusCommon"
                version = "0.1.0"
            }
        val contentRepo = InMemoryFhirRepository(fhirContext)
        val noHeaders = mutableMapOf<String, String>()
        contentRepo.update(contentLib, noHeaders)
        val dataRepo = InMemoryFhirRepository(fhirContext)
        val terminologyRepo = InMemoryFhirRepository(fhirContext)
        val routing =
            SidecarRoutingRepository(
                fhirContext = fhirContext,
                data = dataRepo,
                content = contentRepo,
                terminology = terminologyRepo,
                contentBaseUrl = "http://kr.example",
            )

        val read = routing.read(Library::class.java, IdType("Library", "AtriusCommon"), noHeaders)
        assertEquals("AtriusCommon", read.name)

        // Canonical id must still hit content after normalization
        val byCanonical =
            routing.read(
                Library::class.java,
                IdType("Library", "https://atrius.in/fhir/r4/atrius-in/AtriusCommon"),
                noHeaders,
            )
        assertEquals("AtriusCommon", byCanonical.name)
        assertEquals("AtriusCommon", byCanonical.idElement.idPart)
    }
}
