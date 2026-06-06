package com.atrius.sidecar.fhir

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SidecarFhirContextTest {

    @Test
    fun configuresValidationSupportOnContext() {
        val ctx = newSidecarFhirContext()
        assertNotNull(ctx.validationSupport)
    }
}
