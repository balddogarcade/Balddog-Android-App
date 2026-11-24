package dog.bald.arcade.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ExitSyncCoordinatorTest {

    @Before
    fun setUp() {
        ExitSyncCoordinator.clearForTest()
    }

    @After
    fun tearDown() {
        ExitSyncCoordinator.clearForTest()
    }

    @Test
    fun awaitInFlightReturnsTrueImmediatelyWhenEmpty() = runBlocking {
        assertEquals(0, ExitSyncCoordinator.activeCount())
        assertTrue(ExitSyncCoordinator.awaitInFlight(timeoutMs = 100L))
    }

    @Test
    fun registerTracksInFlightJobsAndAwaitWaitsForCompletion() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val finished = AtomicBoolean(false)

        val job = launch(Dispatchers.Default) {
            gate.await()
            delay(30L)
            finished.set(true)
        }
        ExitSyncCoordinator.register(job)
        assertEquals(1, ExitSyncCoordinator.activeCount())

        val releaseJob = launch(Dispatchers.Default) {
            delay(40L)
            gate.complete(Unit)
        }

        val completed = ExitSyncCoordinator.awaitInFlight(timeoutMs = 3_000L)
        releaseJob.join()

        assertTrue(completed)
        assertTrue(finished.get())
        assertEquals(0, ExitSyncCoordinator.activeCount())
    }

    @Test
    fun completedJobIsNotRetainedAfterRegistration() {
        val completedJob = Job().apply { complete() }
        ExitSyncCoordinator.register(completedJob)
        assertEquals(0, ExitSyncCoordinator.activeCount())
    }

    @Test
    fun awaitInFlightReturnsFalseOnTimeoutWithoutThrowing() = runBlocking {
        val neverFinishes = CompletableDeferred<Unit>()
        val hangingJob = launch(Dispatchers.Default) {
            neverFinishes.await()
        }
        try {
            ExitSyncCoordinator.register(hangingJob)
            assertEquals(1, ExitSyncCoordinator.activeCount())

            val completed = ExitSyncCoordinator.awaitInFlight(timeoutMs = 60L)
            assertFalse(completed)
            assertEquals(1, ExitSyncCoordinator.activeCount())
        } finally {
            hangingJob.cancel()
            hangingJob.join()
        }
        assertEquals(0, ExitSyncCoordinator.activeCount())
    }
}
