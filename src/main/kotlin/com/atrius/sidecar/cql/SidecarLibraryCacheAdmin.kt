package com.atrius.sidecar.cql

import com.atrius.sidecar.api.ClearLibraryCacheResponse
import com.atrius.sidecar.cr.SidecarExpandCache
import com.atrius.sidecar.cr.SidecarKrContentCache
import com.atrius.sidecar.fhir.clearSidecarEvaluationCaches

/** Process-wide CQL / KR library cache invalidation (production KR deploy runbook). */
object SidecarLibraryCacheAdmin {
    fun clearLibraryCaches(): ClearLibraryCacheResponse {
        val stacks = EvaluationLibraryCache.clear()
        val resources = FhirLibraryResourceCaches.clearAll()
        val terminology = ValueSetExpansionCache.clear()
        val krContent = SidecarKrContentCache.clear()
        val expands = SidecarExpandCache.clear()
        val cqf = clearSidecarEvaluationCaches()
        return ClearLibraryCacheResponse(
            cleared =
                listOf(
                    "evaluationLibraryStacks",
                    "fhirLibraryResources",
                    "terminologyExpansions",
                    "krContentResources",
                    "applyExpandResults",
                    "cqfEvaluationSettingsCaches",
                ),
            evaluationStacksRemoved = stacks,
            fhirLibraryResourcesRemoved = resources + krContent,
            terminologyExpansionBucketsRemoved = terminology + expands + cqf,
        )
    }
}
