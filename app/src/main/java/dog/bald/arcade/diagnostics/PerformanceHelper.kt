package dog.bald.arcade.diagnostics

import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

object PerformanceHelper {
    private const val MAX_ATTR_LEN = 100
    private const val MAX_ATTRS_PER_TRACE = 5

    fun startTrace(name: String): Trace? =
        runCatching {
            FirebasePerformance.getInstance().newTrace(name.take(MAX_ATTR_LEN)).apply { start() }
        }.getOrNull()

    fun stopTrace(
        trace: Trace?,
        attributes: Map<String, String> = emptyMap(),
        metrics: Map<String, Long> = emptyMap(),
    ) {
        if (trace == null) return
        runCatching {
            var attrCount = 0
            for ((key, value) in attributes) {
                if (attrCount >= MAX_ATTRS_PER_TRACE) break
                val safeKey = key.trim().take(MAX_ATTR_LEN)
                val safeVal = value.trim().take(MAX_ATTR_LEN)
                if (safeKey.isNotEmpty() && safeVal.isNotEmpty()) {
                    trace.putAttribute(safeKey, safeVal)
                    attrCount++
                }
            }
            metrics.forEach { (key, value) ->
                val safeKey = key.trim().take(MAX_ATTR_LEN)
                if (safeKey.isNotEmpty()) {
                    trace.putMetric(safeKey, value)
                }
            }
            trace.stop()
        }
    }

    inline fun <T> measureTrace(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        metrics: Map<String, Long> = emptyMap(),
        block: () -> T,
    ): T {
        val trace = startTrace(name)
        return try {
            block()
        } finally {
            stopTrace(trace, attributes, metrics)
        }
    }

    suspend inline fun <T> measureTraceSuspend(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        metrics: Map<String, Long> = emptyMap(),
        crossinline block: suspend () -> T,
    ): T {
        val trace = startTrace(name)
        return try {
            block()
        } finally {
            stopTrace(trace, attributes, metrics)
        }
    }
}
