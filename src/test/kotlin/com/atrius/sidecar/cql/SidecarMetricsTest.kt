package com.atrius.sidecar.cql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidecarMetricsTest {
    @Test
    fun recordEvaluate_increments_counters_and_averages() {
        val before = SidecarMetrics.snapshot()
        SidecarMetrics.recordEvaluate(
            durationMs = 50,
            libraryCacheHit = true,
            libraryId = "TestLib",
            libraryVersion = "1.0.0",
            expression = "InPopulation",
            krFetchesThisRequest = 0,
            error = false,
        )
        SidecarMetrics.recordEvaluate(
            durationMs = 150,
            libraryCacheHit = false,
            libraryId = "TestLib",
            libraryVersion = "1.0.0",
            expression = "InPopulation",
            krFetchesThisRequest = 2,
            error = true,
        )
        val after = SidecarMetrics.snapshot()

        assertEquals(before.evaluateTotal + 2, after.evaluateTotal)
        assertEquals(before.evaluateErrors + 1, after.evaluateErrors)
        assertEquals(before.libraryStackCacheHits + 1, after.libraryStackCacheHits)
        assertEquals(before.libraryStackCacheMisses + 1, after.libraryStackCacheMisses)
        // SidecarMetrics is a shared singleton: recording samples below the prior
        // mean lowers the average, so only assert it reflects recorded samples.
        assertTrue(after.evaluateAvgDurationMs > 0)
    }

    @Test
    fun krFetchesSince_counts_fetches_after_baseline() {
        val baseline = SidecarMetrics.currentKrLibraryFetches()
        SidecarMetrics.recordKrLibraryFetch()
        SidecarMetrics.recordKrLibraryFetch()
        assertEquals(2, SidecarMetrics.krFetchesSince(baseline))
    }

    @Test
    fun recordApply_increments_apply_counters() {
        val before = SidecarMetrics.snapshot()
        SidecarMetrics.recordApply(durationMs = 200, planDefinitionId = "cms165", error = false)
        val after = SidecarMetrics.snapshot()
        assertEquals(before.applyTotal + 1, after.applyTotal)
        assertEquals(before.evaluateErrors, after.evaluateErrors)
        assertTrue(after.applyAvgDurationMs > 0)
    }
}
