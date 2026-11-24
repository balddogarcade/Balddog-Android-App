package dog.bald.arcade.data

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
        return withTimeoutOrNull(timeoutMs) {
            snapshot.joinAll()
            true
        } ?: false
    }

    internal fun activeCount(): Int = synchronized(inFlightJobs) { inFlightJobs.size }

    internal fun clearForTest() {
        synchronized(inFlightJobs) {
            inFlightJobs.clear()
        }
    }
}
