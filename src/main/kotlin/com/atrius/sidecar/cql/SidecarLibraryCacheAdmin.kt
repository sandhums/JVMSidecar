package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ClearLibraryCacheResponse

/** Process-wide CQL / KR library cache invalidation (production KR deploy runbook). */
object SidecarLibraryCacheAdmin {
    fun clearLibraryCaches(): ClearLibraryCacheResponse {
        val stacks = EvaluationLibraryCache.clear()
        val resources = FhirLibraryResourceCaches.clearAll()
        val terminology = ValueSetExpansionCache.clear()
        return ClearLibraryCacheResponse(
            cleared =
                listOf(
                    "evaluationLibraryStacks",
                    "fhirLibraryResources",
                    "terminologyExpansions",
                ),
            evaluationStacksRemoved = stacks,
            fhirLibraryResourcesRemoved = resources,
            terminologyExpansionBucketsRemoved = terminology,
        )
    }
}
