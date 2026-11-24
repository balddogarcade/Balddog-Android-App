package dog.bald.arcade.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class PendingScoreQueueTest {

    private val json = Json { ignoreUnknownKeys = true }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryScoreQueueStore(
        initial: List<PendingScoreSubmission> = emptyList()
    ) : ScoreQueueStore {
        private var entries: List<PendingScoreSubmission> = initial.toList()

        @Synchronized
        override fun read(): List<PendingScoreSubmission> = entries.toList()

        @Synchronized
        override fun write(entries: List<PendingScoreSubmission>) {
            this.entries = entries.toList()
        }

        @Synchronized
        override fun clear() {
            entries = emptyList()
        }
    }

    private class TempDirReplayFileHandler(private val pendingDir: File) : ReplayFileHandler {
        init {
            pendingDir.mkdirs()
        }

        override fun existsAndNonEmpty(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            val f = File(path)
            return f.exists() && f.isFile && f.length() > 0L
        }

        override fun sha256Hex(path: String): String? {
            val f = File(path)
            if (!f.exists() || !f.isFile || f.length() <= 0L) return null
            val bytes = f.readBytes()
            if (bytes.isEmpty()) return null
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }

        override fun deleteFile(path: String?) {
            if (!path.isNullOrBlank()) {
                File(path).delete()
            }
        }

        override fun clearAllReplayFiles() {
            if (pendingDir.exists()) {
                pendingDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        }
    }

    private data class SubmitCall(
        val gameId: String,
        val score: Long,
        val replayPath: String,
        val replaySizeBytes: Int?,
        val replaySha256: String
    )

    private class FakeScoreSyncClient(
        private val uid: String?,
        private val rankScoreProvider: suspend (String) -> Long? = { null },
        private val uploadHandler: suspend (String, String) -> UploadedReplay? = { gameId, localPath ->
            val bytes = File(localPath).readBytes()
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            UploadedReplay("$gameId/${uid ?: "anon"}/uploaded.bsv.gz", bytes.size, sha)
        },
        private val submitHandler: suspend (SubmitCall) -> SubmitScoreResultDto? = {
            SubmitScoreResultDto(accepted = true, isNewRecord = true, bestScore = it.score, rank = 1L)
        }
    ) : ScoreSyncClient {
        val uploadCalls = mutableListOf<Pair<String, String>>()
        val submitCalls = mutableListOf<SubmitCall>()
        val activeFlushes = AtomicInteger(0)
        val maxConcurrentFlushes = AtomicInteger(0)

        override fun currentUserId(): String? = uid

        override suspend fun fetchMyGameRankScore(gameId: String): Long? {
            val active = activeFlushes.incrementAndGet()
            maxConcurrentFlushes.updateAndGet { maxOf(it, active) }
            return try {
                rankScoreProvider(gameId)
            } finally {
                activeFlushes.decrementAndGet()
            }
        }

        override suspend fun uploadReplay(gameId: String, localPath: String): UploadedReplay? {
            synchronized(uploadCalls) { uploadCalls += gameId to localPath }
            return uploadHandler(gameId, localPath)
        }

        override suspend fun submitHighScore(
            gameId: String,
            score: Long,
            romSha256: String,
            creditsUsed: Int,
            isHardcore: Boolean,
            replayPath: String,
            replaySizeBytes: Int?,
            replaySha256: String
        ): SubmitScoreResultDto? {
            val call = SubmitCall(gameId, score, replayPath, replaySizeBytes, replaySha256)
            synchronized(submitCalls) { submitCalls += call }
            return submitHandler(call)
        }
    }

    private fun createReplayFile(name: String, content: ByteArray = "replay-bytes-$name".toByteArray()): File {
        val dir = File(tempFolder.root, "pending_replays").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(content) }
    }

    private fun newEngine(store: InMemoryScoreQueueStore): PendingScoreQueueEngine {
        val dir = File(tempFolder.root, "pending_replays").apply { mkdirs() }
        return PendingScoreQueueEngine(store, TempDirReplayFileHandler(dir))
    }

    @Test
    fun legacyJsonWithoutUserIdDeserializesSafelyAndDropsWithReplayDeletion() {
        val legacyFile = createReplayFile("legacy.bsv.gz")
        val legacyJson = """
            [
              {
                "gameId": "game-1",
                "score": 4200,
                "romSha256": "abc",
                "replayLocalPath": "${legacyFile.absolutePath.replace("\\", "\\\\")}"
              }
            ]
        """.trimIndent()
        val parsed = json.decodeFromString<List<PendingScoreSubmission>>(legacyJson)
        assertEquals(1, parsed.size)
        assertEquals(null, parsed[0].userId)

        val store = InMemoryScoreQueueStore(parsed)
        val engine = newEngine(store)

        assertEquals(0, engine.pendingCount("user-a"))
        assertTrue(store.read().isEmpty())
        assertFalse(legacyFile.exists())
    }

    @Test
    fun userIdScopingDropsForeignAndNullEntriesAndDeletesReplayFilesOnFlush() = runBlocking {
        val fileA = createReplayFile("a.bsv.gz")
        val fileB = createReplayFile("b.bsv.gz")
        val fileNull = createReplayFile("null.bsv.gz")

        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(userId = "user-a", gameId = "g1", score = 1000, romSha256 = "sha", replayLocalPath = fileA.absolutePath),
                PendingScoreSubmission(userId = "user-b", gameId = "g1", score = 2000, romSha256 = "sha", replayLocalPath = fileB.absolutePath),
                PendingScoreSubmission(userId = null, gameId = "g2", score = 3000, romSha256 = "sha", replayLocalPath = fileNull.absolutePath)
            )
        )
        val engine = newEngine(store)
        val client = FakeScoreSyncClient(uid = "user-b")

        val synced = engine.flushAll(client)
        assertEquals(1, synced)
        assertEquals(1, client.submitCalls.size)
        assertEquals(2000L, client.submitCalls[0].score)
        assertTrue(store.read().isEmpty())
        assertFalse(fileA.exists())
        assertFalse(fileB.exists())
        assertFalse(fileNull.exists())
    }

    @Test
    fun clearRemovesAllQueueEntriesAndPendingReplayFiles() {
        val file1 = createReplayFile("one.bsv.gz")
        val file2 = createReplayFile("two.bsv.gz")
        val store = InMemoryScoreQueueStore()
        val engine = newEngine(store)

        engine.enqueue(PendingScoreSubmission(userId = "user-a", gameId = "g1", score = 500, romSha256 = "sha", replayLocalPath = file1.absolutePath))
        engine.enqueue(PendingScoreSubmission(userId = "user-a", gameId = "g2", score = 900, romSha256 = "sha", replayLocalPath = file2.absolutePath))
        assertEquals(2, engine.pendingCount("user-a"))

        engine.clear()

        assertEquals(0, engine.pendingCount("user-a"))
        assertTrue(store.read().isEmpty())
        assertFalse(file1.exists())
        assertFalse(file2.exists())
    }

    @Test
    fun concurrentEnqueueDuringFlushPreservesHigherScoreAndNewGame() = runBlocking {
        val initialFile = createReplayFile("initial.bsv.gz")
        val higherFile = createReplayFile("higher.bsv.gz")
        val newGameFile = createReplayFile("new_game.bsv.gz")

        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(
                    userId = "user-a",
                    gameId = "g1",
                    score = 1000L,
                    romSha256 = "sha",
                    replayLocalPath = initialFile.absolutePath,
                    queuedAtMs = 100L
                )
            )
        )
        val engine = newEngine(store)
        val uploadStarted = CompletableDeferred<Unit>()
        val allowUploadFinish = CompletableDeferred<Unit>()

        val client = FakeScoreSyncClient(
            uid = "user-a",
            uploadHandler = { gameId, localPath ->
                uploadStarted.complete(Unit)
                allowUploadFinish.await()
                val bytes = File(localPath).readBytes()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                UploadedReplay("$gameId/user-a/u1.bsv.gz", bytes.size, sha)
            }
        )

        val flushJob = async(Dispatchers.IO) { engine.flushAll(client) }
        uploadStarted.await()

        engine.enqueue(
            PendingScoreSubmission(
                userId = "user-a",
                gameId = "g1",
                score = 2500L,
                romSha256 = "sha",
                replayLocalPath = higherFile.absolutePath,
                queuedAtMs = 200L
            )
        )
        engine.enqueue(
            PendingScoreSubmission(
                userId = "user-a",
                gameId = "g2",
                score = 900L,
                romSha256 = "sha",
                replayLocalPath = newGameFile.absolutePath,
                queuedAtMs = 201L
            )
        )

        allowUploadFinish.complete(Unit)
        val synced = flushJob.await()

        assertEquals(1, synced)
        val remaining = engine.read("user-a").associateBy { it.gameId }
        assertEquals(2, remaining.size)
        assertEquals(2500L, remaining["g1"]?.score)
        assertEquals(900L, remaining["g2"]?.score)
        assertFalse(initialFile.exists())
        assertTrue(higherFile.exists())
        assertTrue(newGameFile.exists())
    }

    @Test
    fun concurrentFlushAllCallsAreSerializedByMutex() = runBlocking {
        val replayFile = createReplayFile("mutex.bsv.gz")
        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(
                    userId = "user-a",
                    gameId = "g1",
                    score = 1500L,
                    romSha256 = "sha",
                    replayLocalPath = replayFile.absolutePath
                )
            )
        )
        val engine = newEngine(store)
        val client = FakeScoreSyncClient(
            uid = "user-a",
            rankScoreProvider = {
                kotlinx.coroutines.delay(40)
                null
            }
        )

        val counts = listOf(
            async(Dispatchers.Default) { engine.flushAll(client) },
            async(Dispatchers.Default) { engine.flushAll(client) }
        ).awaitAll()

        assertEquals(1, client.maxConcurrentFlushes.get())
        assertEquals(1, counts.sum())
        assertEquals(1, client.submitCalls.size)
    }

    @Test
    fun transientUploadFailureKeepsEntryQueuedWithoutCallingSubmitHighScore() = runBlocking {
        val replayFile = createReplayFile("transient.bsv.gz")
        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(
                    userId = "user-a",
                    gameId = "g1",
                    score = 1800L,
                    romSha256 = "sha",
                    replayLocalPath = replayFile.absolutePath
                )
            )
        )
        val engine = newEngine(store)
        val client = FakeScoreSyncClient(
            uid = "user-a",
            uploadHandler = { _, _ -> null }
        )

        val synced = engine.flushAll(client)
        assertEquals(0, synced)
        assertEquals(1, client.uploadCalls.size)
        assertEquals(0, client.submitCalls.size)
        assertEquals(1, engine.pendingCount("user-a"))
        assertTrue(replayFile.exists())
    }

    @Test
    fun all022And023PermanentRejectionsDropEntryAndDeleteReplayFile() = runBlocking {
        val reasons = listOf(
            "anonymous_not_allowed",
            "invalid_score",
            "hardcore_required",
            "single_credit_only",
            "game_not_found",
            "leaderboard_not_configured",
            "rom_mismatch",
            "score_exceeds_max",
            "replay_required",
            "invalid_replay_path",
            "invalid_replay_metadata",
            "invalid_replay_sha256",
            "replay_already_used"
        )

        for (reason in reasons) {
            val replayFile = createReplayFile("perm_$reason.bsv.gz")
            val store = InMemoryScoreQueueStore(
                listOf(
                    PendingScoreSubmission(
                        userId = "user-a",
                        gameId = "g1",
                        score = 1200L,
                        romSha256 = "sha",
                        replayLocalPath = replayFile.absolutePath
                    )
                )
            )
            val engine = newEngine(store)
            val client = FakeScoreSyncClient(
                uid = "user-a",
                submitHandler = { SubmitScoreResultDto(accepted = false, reason = reason) }
            )

            val synced = engine.flushAll(client)
            assertEquals("Expected 0 synced for $reason", 0, synced)
            assertEquals("Expected empty queue for $reason", 0, engine.pendingCount("user-a"))
            assertFalse("Expected replay file deleted for $reason", replayFile.exists())
        }
    }

    @Test
    fun acceptedSubmissionComputesSha256PassesToSubmitAndDeletesReplayFile() = runBlocking {
        val payload = "verified-replay-content".toByteArray()
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        val replayFile = createReplayFile("accepted.bsv.gz", payload)
        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(
                    userId = "user-a",
                    gameId = "g1",
                    score = 7777L,
                    romSha256 = "romsha",
                    replayLocalPath = replayFile.absolutePath
                )
            )
        )
        val engine = newEngine(store)
        val client = FakeScoreSyncClient(
            uid = "user-a",
            uploadHandler = { gameId, _ ->
                // Even if uploadHandler omits sha256, engine computes 64-hex sha256 from local replay bytes
                UploadedReplay("$gameId/user-a/uuid.bsv.gz", payload.size, "")
            }
        )

        val synced = engine.flushAll(client)
        assertEquals(1, synced)
        assertEquals(1, client.submitCalls.size)
        assertEquals(expectedSha, client.submitCalls[0].replaySha256)
        assertEquals(0, engine.pendingCount("user-a"))
        assertFalse(replayFile.exists())
    }

    @Test
    fun scoreLowerThanCurrentBestSkipsUploadAndSubmitAndDeletesReplayFile() = runBlocking {
        val replayFile = createReplayFile("obsolete.bsv.gz")
        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(
                    userId = "user-a",
                    gameId = "g1",
                    score = 3000L,
                    romSha256 = "sha",
                    replayLocalPath = replayFile.absolutePath
                )
            )
        )
        val engine = newEngine(store)
        val client = FakeScoreSyncClient(
            uid = "user-a",
            rankScoreProvider = { 5000L }
        )

        val synced = engine.flushAll(client)
        assertEquals(0, synced)
        assertEquals(0, client.uploadCalls.size)
        assertEquals(0, client.submitCalls.size)
        assertEquals(0, engine.pendingCount("user-a"))
        assertFalse(replayFile.exists())
    }

    @Test
    fun rankFetchNetworkFailureDoesNotDropValidScore() = runBlocking {
        val replayFile = createReplayFile("rank_offline.bsv.gz")
        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(
                    userId = "user-a",
                    gameId = "g1",
                    score = 3000L,
                    romSha256 = "sha",
                    replayLocalPath = replayFile.absolutePath
                )
            )
        )
        val engine = newEngine(store)
        val client = FakeScoreSyncClient(
            uid = "user-a",
            rankScoreProvider = { throw IOException("network down") }
        )

        val synced = engine.flushAll(client)
        assertEquals(1, synced)
        assertEquals(1, client.uploadCalls.size)
        assertEquals(1, client.submitCalls.size)
        assertEquals(0, engine.pendingCount("user-a"))
        assertFalse(replayFile.exists())
    }

    @Test
    fun missingOrEmptyReplayFileIsDroppedWithoutUploadOrSubmit() = runBlocking {
        val emptyFile = createReplayFile("empty.bsv.gz", ByteArray(0))
        val missingPath = File(tempFolder.root, "pending_replays/does_not_exist.bsv.gz").absolutePath
        val store = InMemoryScoreQueueStore(
            listOf(
                PendingScoreSubmission(userId = "user-a", gameId = "g1", score = 1000L, romSha256 = "sha", replayLocalPath = emptyFile.absolutePath),
                PendingScoreSubmission(userId = "user-a", gameId = "g2", score = 2000L, romSha256 = "sha", replayLocalPath = missingPath)
            )
        )
        val engine = newEngine(store)
        val client = FakeScoreSyncClient(uid = "user-a")

        val synced = engine.flushAll(client)
        assertEquals(0, synced)
        assertEquals(0, client.uploadCalls.size)
        assertEquals(0, client.submitCalls.size)
        assertEquals(0, engine.pendingCount("user-a"))
        assertFalse(emptyFile.exists())
    }
}
