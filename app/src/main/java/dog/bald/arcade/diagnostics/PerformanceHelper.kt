package dog.bald.arcade.diagnostics

import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

object PerformanceHelper {
    fun startTrace(name: String): Trace? =
        runCatching { FirebasePerformance.getInstance().newTrace(name).apply { start() } }.getOrNull()

    fun stopTrace(trace: Trace?, attributes: Map<String, String> = emptyMap()) {
        if (trace == null) return
        runCatching {
            attributes.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    trace.putAttribute(key, value)
                }
            }
            trace.stop()
        }
    }

    inline fun <T> measureTrace(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        block: () -> T
    ): T {
        val trace = startTrace(name)
        return try {
            block()
        } finally {
            stopTrace(trace, attributes)
        }
    }
}
