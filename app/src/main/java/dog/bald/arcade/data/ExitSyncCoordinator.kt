package dog.bald.arcade.data

import dog.bald.arcade.diagnostics.CrashlyticsHelper
import dog.bald.arcade.diagnostics.PerformanceHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

object ExitSyncCoordinator {
    private val inFlightJobs = Collections.synchronizedSet(mutableSetOf<Job>())

    fun register(job: Job): Job {
        if (job.isCompleted) return job
        inFlightJobs.add(job)
        job.invokeOnCompletion {
            inFlightJobs.remove(job)
        }
        if (job.isCompleted) {
            inFlightJobs.remove(job)
        }
        return job
    }

    suspend fun awaitInFlight(timeoutMs: Long = 8_000L): Boolean {
        val snapshot = synchronized(inFlightJobs) { inFlightJobs.toList() }
        if (snapshot.isEmpty()) return true
        val trace = PerformanceHelper.startTrace("exit_sync_trace")
        var completed = false
        return try {
            completed =
                withTimeoutOrNull(timeoutMs) {
                    snapshot.joinAll()
                    true
                } ?: false
            if (!completed) {
                CrashlyticsHelper.logSyncEvent("exit_sync_timeout", "jobs=${snapshot.size}")
            }
            completed
        } finally {
            PerformanceHelper.stopTrace(
                trace,
                attributes = mapOf("outcome" to if (completed) "completed" else "timeout"),
                metrics = mapOf("jobs_count" to snapshot.size.toLong()),
            )
        }
    }

    internal fun activeCount(): Int = synchronized(inFlightJobs) { inFlightJobs.size }

    internal fun clearForTest() {
        synchronized(inFlightJobs) {
            inFlightJobs.clear()
        }
    }
}
