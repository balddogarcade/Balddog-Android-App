package dog.bald.arcade.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PendingMatchHistoryQueueTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class InMemoryMatchHistoryStore(
        initial: List<PendingMatchHistoryEntry> = emptyList()
    ) : MatchHistoryQueueStore {
        private var entries: List<PendingMatchHistoryEntry> = initial.toList()

        @Synchronized
        override fun read(): List<PendingMatchHistoryEntry> = entries.toList()

        @Synchronized
        override fun write(entries: List<PendingMatchHistoryEntry>) {
            this.entries = entries.toList()
        }

        @Synchronized
        override fun clear() {
            entries = emptyList()
        }
    }

    private data class RecordedCall(
        val clientSessionId: String,
        val gameId: String,
        val mode: String,
        val players: List<MatchHistoryPlayerDto>,
        val durationSeconds: Int,
        val score: Long?
    )

    private class FakeMatchHistorySyncClient(
        private val uid: String?,
        private val username: String? = "PlayerOne",
        private val recordHandler: suspend (RecordedCall) -> Unit = {}
    ) : MatchHistorySyncClient {
        val calls = mutableListOf<RecordedCall>()
        val activeFlushes = AtomicInteger(0)
        val maxConcurrentFlushes = AtomicInteger(0)

        override fun currentUserId(): String? = uid

        override suspend fun fetchMyUsername(): String? = username

        override suspend fun recordMatchHistory(
            clientSessionId: String,
            gameId: String,
            mode: String,
            roomId: String?,
            isPrivate: Boolean,
            players: List<MatchHistoryPlayerDto>,
            startedAtIso: String,
            endedAtIso: String,
            durationSeconds: Int,
            score: Long?
        ) {
            val active = activeFlushes.incrementAndGet()
            maxConcurrentFlushes.updateAndGet { maxOf(it, active) }
            try {
                val call = RecordedCall(clientSessionId, gameId, mode, players, durationSeconds, score)
                synchronized(calls) { calls += call }
                recordHandler(call)
            } finally {
                activeFlushes.decrementAndGet()
            }
        }
    }

    private fun entry(
        userId: String?,
        sessionId: String,
        durationSeconds: Int = 30,
        score: Long? = 1000L,
        queuedAtMs: Long = 100L
    ) = PendingMatchHistoryEntry(
        userId = userId,
        clientSessionId = sessionId,
        gameId = "game-1",
        mode = "local",
        startedAtIso = "2026-11-24T00:00:00Z",
        endedAtIso = "2026-11-24T00:00:30Z",
        durationSeconds = durationSeconds,
        score = score,
        queuedAtMs = queuedAtMs
    )

    @Test
    fun legacyJsonWithoutUserIdDeserializesSafelyAndIsDropped() {
        val legacyJson = """
            [
              {
                "clientSessionId": "s-legacy",
                "gameId": "game-1",
                "mode": "local",
                "startedAtIso": "2026-11-24T00:00:00Z",
                "endedAtIso": "2026-11-24T00:00:30Z",
                "durationSeconds": 30
              }
            ]
        """.trimIndent()
        val parsed = json.decodeFromString<List<PendingMatchHistoryEntry>>(legacyJson)
        assertEquals(null, parsed[0].userId)

        val store = InMemoryMatchHistoryStore(parsed)
        val engine = PendingMatchHistoryQueueEngine(store)

        assertEquals(0, engine.pendingCount("user-a"))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun userIdScopingIsolatesUsersAndFlushDropsForeignEntries() = runBlocking {
        val store = InMemoryMatchHistoryStore(
            listOf(
                entry(userId = "user-a", sessionId = "s-a"),
                entry(userId = "user-b", sessionId = "s-b"),
                entry(userId = null, sessionId = "s-null")
            )
        )
        val engine = PendingMatchHistoryQueueEngine(store)
        val client = FakeMatchHistorySyncClient(uid = "user-a", username = "Alice")

        val synced = engine.flushAll(client)
        assertEquals(1, synced)
        assertEquals(1, client.calls.size)
        assertEquals("s-a", client.calls[0].clientSessionId)
        assertEquals("user-a", client.calls[0].players.single().userId)
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun clearRemovesAllEntries() {
        val store = InMemoryMatchHistoryStore()
        val engine = PendingMatchHistoryQueueEngine(store)

        engine.enqueue(entry(userId = "user-a", sessionId = "s1"))
        engine.enqueue(entry(userId = "user-a", sessionId = "s2"))
        assertEquals(2, engine.pendingCount("user-a"))

        engine.clear()
        assertEquals(0, engine.pendingCount("user-a"))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun concurrentEnqueueUpdateToInFlightSessionPreservesHigherDurationAndScore() = runBlocking {
        val store = InMemoryMatchHistoryStore(
            listOf(entry(userId = "user-a", sessionId = "s1", durationSeconds = 10, score = 100L, queuedAtMs = 100L))
        )
        val engine = PendingMatchHistoryQueueEngine(store)
        val recordStarted = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        val client = FakeMatchHistorySyncClient(
            uid = "user-a",
            recordHandler = { call ->
                if (call.durationSeconds == 10) {
                    recordStarted.complete(Unit)
                    allowFinish.await()
                }
            }
        )

        val flushJob = async(Dispatchers.IO) { engine.flushAll(client) }
        recordStarted.await()

        engine.enqueue(
            entry(userId = "user-a", sessionId = "s1", durationSeconds = 45, score = 950L, queuedAtMs = 200L)
        )

        allowFinish.complete(Unit)
        val firstSynced = flushJob.await()
        assertEquals(1, firstSynced)

        val remaining = engine.read("user-a")
        assertEquals(1, remaining.size)
        assertEquals("s1", remaining[0].clientSessionId)
        assertEquals(45, remaining[0].durationSeconds)
        assertEquals(950L, remaining[0].score)

        val secondSynced = engine.flushAll(client)
        assertEquals(1, secondSynced)
        assertEquals(2, client.calls.size)
        assertEquals(45, client.calls[1].durationSeconds)
        assertEquals(950L, client.calls[1].score)
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun concurrentFlushAllCallsAreSerializedByMutex() = runBlocking {
        val store = InMemoryMatchHistoryStore(
            listOf(entry(userId = "user-a", sessionId = "s1"))
        )
        val engine = PendingMatchHistoryQueueEngine(store)
        val client = FakeMatchHistorySyncClient(
            uid = "user-a",
            recordHandler = { delay(40) }
        )

        val counts = listOf(
            async(Dispatchers.Default) { engine.flushAll(client) },
            async(Dispatchers.Default) { engine.flushAll(client) }
        ).awaitAll()

        assertEquals(1, client.maxConcurrentFlushes.get())
        assertEquals(1, counts.sum())
        assertEquals(1, client.calls.size)
    }

    @Test
    fun permanentValidationErrorsDropEntryWhileTransientErrorsKeepEntry() = runBlocking {
        val permanentMessages = listOf(
            "Game not found",
            "Valid started room not found for match",
            "Room metadata mismatch",
            "Participant mismatch with room",
            "Netplay match requires at least 2 participants",
            "Cannot overwrite another session",
            "Registered user required",
            "room_id is required for lan/online matches",
            "Invalid mode",
            "Invalid match mode: foo",
            "User is not a participant of this match"
        )

        for (msg in permanentMessages) {
            val store = InMemoryMatchHistoryStore(listOf(entry(userId = "user-a", sessionId = "s-perm")))
            val engine = PendingMatchHistoryQueueEngine(store)
            val client = FakeMatchHistorySyncClient(
                uid = "user-a",
                recordHandler = { throw IllegalStateException(msg) }
            )

            val synced = engine.flushAll(client)
            assertEquals("Expected 0 synced for $msg", 0, synced)
            assertEquals("Expected permanent drop for $msg", 0, engine.pendingCount("user-a"))
        }

        val transientStore = InMemoryMatchHistoryStore(listOf(entry(userId = "user-a", sessionId = "s-tmp")))
        val transientEngine = PendingMatchHistoryQueueEngine(transientStore)
        val transientClient = FakeMatchHistorySyncClient(
            uid = "user-a",
            recordHandler = { throw IOException("Network timeout") }
        )

        val synced = transientEngine.flushAll(transientClient)
        assertEquals(0, synced)
        assertEquals(1, transientEngine.pendingCount("user-a"))
    }
}
