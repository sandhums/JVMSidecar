package com.atrius.sidecar.cql

import com.atrius.sidecar.api.SidecarMetricsSnapshot
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Process-wide counters for minimal production observability (no Prometheus in v1).
 * Per-request details are also logged with target `sidecar_evaluate_metrics`.
 */
object SidecarMetrics {
    private val logger = LoggerFactory.getLogger(SidecarMetrics::class.java)

    private val evaluateTotal = AtomicLong()
    private val evaluateErrors = AtomicLong()
    private val evaluateDurationMsSum = AtomicLong()
    private val libraryStackCacheHits = AtomicLong()
    private val libraryStackCacheMisses = AtomicLong()
    private val krLibraryFetches = AtomicLong()
    private val applyTotal = AtomicLong()
    private val applyDurationMsSum = AtomicLong()

    fun recordEvaluate(
        durationMs: Long,
        libraryCacheHit: Boolean?,
        libraryId: String,
        libraryVersion: String?,
        expression: String,
        krFetchesThisRequest: Long,
        error: Boolean,
    ) {
        evaluateTotal.incrementAndGet()
        evaluateDurationMsSum.addAndGet(durationMs)
        if (error) evaluateErrors.incrementAndGet()
        when (libraryCacheHit) {
            true -> libraryStackCacheHits.incrementAndGet()
            false -> libraryStackCacheMisses.incrementAndGet()
            null -> Unit
        }
        logger.info(
            "sidecar evaluate completed libraryId={} libraryVersion={} expression={} durationMs={} " +
                "libraryStackCacheHit={} krLibraryFetches={} error={}",
            libraryId,
            libraryVersion ?: "",
            expression,
            durationMs,
            libraryCacheHit,
            krFetchesThisRequest,
            error,
        )
    }

    fun recordApply(durationMs: Long, planDefinitionId: String?, error: Boolean) {
        applyTotal.incrementAndGet()
        applyDurationMsSum.addAndGet(durationMs)
        logger.info(
            "sidecar apply completed planDefinitionId={} durationMs={} error={}",
            planDefinitionId ?: "",
            durationMs,
            error,
        )
    }

    fun recordKrLibraryFetch() {
        krLibraryFetches.incrementAndGet()
    }

    fun currentKrLibraryFetches(): Long = krLibraryFetches.get()

    fun krFetchesSince(baseline: Long): Long = krLibraryFetches.get() - baseline

    fun snapshot(): SidecarMetricsSnapshot {
        val evals = evaluateTotal.get()
        val applies = applyTotal.get()
        val evalMs = evaluateDurationMsSum.get()
        val applyMs = applyDurationMsSum.get()
        return SidecarMetricsSnapshot(
            evaluateTotal = evals,
            evaluateErrors = evaluateErrors.get(),
            evaluateAvgDurationMs = if (evals > 0) evalMs.toDouble() / evals else 0.0,
            applyTotal = applies,
            applyAvgDurationMs = if (applies > 0) applyMs.toDouble() / applies else 0.0,
            libraryStackCacheHits = libraryStackCacheHits.get(),
            libraryStackCacheMisses = libraryStackCacheMisses.get(),
            krLibraryFetches = krLibraryFetches.get(),
        )
    }
}
