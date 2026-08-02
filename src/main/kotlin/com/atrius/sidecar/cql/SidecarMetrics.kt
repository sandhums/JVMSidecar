package com.atrius.sidecar.cql

import com.atrius.sidecar.api.SidecarMetricsSnapshot
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Process-wide counters for production observability.
 * Prometheus text is served at `GET /metrics`; JSON remains at `GET /metrics.json`
 * (or `Accept: application/json`).
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

    /**
     * Prometheus exposition format (0.0.4). Counters use `_total` / `_sum` suffixes;
     * averages are gauges derived from the same atomics as [snapshot].
     */
    fun prometheusText(service: String = "cql-sidecar"): String {
        val s = snapshot()
        val label = """service="$service""""
        val evalSum = evaluateDurationMsSum.get()
        val applySum = applyDurationMsSum.get()
        return buildString {
            fun helpType(name: String, help: String, type: String) {
                append("# HELP ").append(name).append(' ').append(help).append('\n')
                append("# TYPE ").append(name).append(' ').append(type).append('\n')
            }
            fun sample(name: String, value: Number) {
                append(name).append('{').append(label).append("} ").append(value).append('\n')
            }

            helpType("sidecar_evaluate_total", "Total evaluate/expression requests", "counter")
            sample("sidecar_evaluate_total", s.evaluateTotal)

            helpType("sidecar_evaluate_errors_total", "Evaluate requests that failed", "counter")
            sample("sidecar_evaluate_errors_total", s.evaluateErrors)

            helpType(
                "sidecar_evaluate_duration_ms_sum",
                "Cumulative evaluate wall time in milliseconds",
                "counter",
            )
            sample("sidecar_evaluate_duration_ms_sum", evalSum)

            helpType(
                "sidecar_evaluate_avg_duration_ms",
                "Mean evaluate duration in milliseconds",
                "gauge",
            )
            sample("sidecar_evaluate_avg_duration_ms", s.evaluateAvgDurationMs)

            helpType("sidecar_apply_total", "Total PlanDefinition/ActivityDefinition apply requests", "counter")
            sample("sidecar_apply_total", s.applyTotal)

            helpType(
                "sidecar_apply_duration_ms_sum",
                "Cumulative apply wall time in milliseconds",
                "counter",
            )
            sample("sidecar_apply_duration_ms_sum", applySum)

            helpType("sidecar_apply_avg_duration_ms", "Mean apply duration in milliseconds", "gauge")
            sample("sidecar_apply_avg_duration_ms", s.applyAvgDurationMs)

            helpType(
                "sidecar_library_stack_cache_hits_total",
                "Prepared CQL library stack cache hits",
                "counter",
            )
            sample("sidecar_library_stack_cache_hits_total", s.libraryStackCacheHits)

            helpType(
                "sidecar_library_stack_cache_misses_total",
                "Prepared CQL library stack cache misses",
                "counter",
            )
            sample("sidecar_library_stack_cache_misses_total", s.libraryStackCacheMisses)

            helpType(
                "sidecar_kr_library_fetches_total",
                "KR FHIR Library resource HTTP fetches (process lifetime)",
                "counter",
            )
            sample("sidecar_kr_library_fetches_total", s.krLibraryFetches)
        }
    }
}
