package dog.bald.arcade.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class PendingScoreSubmission(
    val userId: String? = null,
    val gameId: String,
    val score: Long,
    val romSha256: String,
    val creditsUsed: Int = 1,
    val isHardcore: Boolean = true,
    val replayLocalPath: String? = null,
    val queuedAtMs: Long = System.currentTimeMillis()
)

internal interface ScoreQueueStore {
    fun read(): List<PendingScoreSubmission>
    fun write(entries: List<PendingScoreSubmission>)
    fun clear()
}

internal interface ReplayFileHandler {
    fun existsAndNonEmpty(path: String?): Boolean
    fun sha256Hex(path: String): String?
    fun deleteFile(path: String?)
    fun clearAllReplayFiles()
}

internal data class UploadedReplay(
    val path: String,
    val sizeBytes: Int,
    val sha256: String
)

internal interface ScoreSyncClient {
    fun currentUserId(): String?
    suspend fun fetchMyGameRankScore(gameId: String): Long?
    suspend fun uploadReplay(gameId: String, localPath: String): UploadedReplay?
    suspend fun submitHighScore(
        gameId: String,
        score: Long,
        romSha256: String,
        creditsUsed: Int,
        isHardcore: Boolean,
        replayPath: String,
        replaySizeBytes: Int?,
        replaySha256: String
    ): SubmitScoreResultDto?
}

internal class PendingScoreQueueEngine(
    private val store: ScoreQueueStore,
    private val replayFiles: ReplayFileHandler
) {
    private val lock = Any()
    private val flushMutex = Mutex()

    fun isPermanentRejection(reason: String?): Boolean {
        return reason != null && reason in permanentRejectionReasons
    }

    fun read(currentUserId: String?): List<PendingScoreSubmission> {
        if (currentUserId.isNullOrBlank()) return emptyList()
        val filesToDelete = mutableListOf<String?>()
        val own = synchronized(lock) {
            val all = store.read()
            val foreign = all.filter { it.userId.isNullOrBlank() || it.userId != currentUserId }
            val matching = all.filter { it.userId == currentUserId }
            if (foreign.isNotEmpty() && !flushMutex.isLocked) {
                foreign.forEach { filesToDelete += it.replayLocalPath }
                store.write(matching)
            }
            matching
        }
        filesToDelete.forEach { replayFiles.deleteFile(it) }
        return own
    }

    fun pendingCount(currentUserId: String?): Int {
        return read(currentUserId).size
    }

    fun enqueue(entry: PendingScoreSubmission) {
        val uid = entry.userId?.takeIf { it.isNotBlank() } ?: return
        if (entry.gameId.isBlank() || entry.score <= 0L) return

        val filesToDelete = mutableListOf<String>()
        synchronized(lock) {
            val current = store.read().toMutableList()
            val existingIdx = current.indexOfFirst { it.userId == uid && it.gameId == entry.gameId }
            if (existingIdx >= 0) {
                val prev = current[existingIdx]
                if (entry.score > prev.score) {
                    current[existingIdx] = entry
                    if (!flushMutex.isLocked &&
                        !prev.replayLocalPath.isNullOrBlank() &&
                        prev.replayLocalPath != entry.replayLocalPath
                    ) {
                        filesToDelete += prev.replayLocalPath
                    }
                } else if (!entry.replayLocalPath.isNullOrBlank() &&
                    entry.replayLocalPath != prev.replayLocalPath
                ) {
                    filesToDelete += entry.replayLocalPath
                }
            } else {
                current.add(entry)
            }
            store.write(current)
        }
        filesToDelete.forEach { replayFiles.deleteFile(it) }
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
        }
        replayFiles.clearAllReplayFiles()
    }

    suspend fun flushAll(client: ScoreSyncClient): Int {
        val uid = client.currentUserId()?.takeIf { it.isNotBlank() } ?: return 0

        return flushMutex.withLock {
            val foreignPaths = mutableListOf<String?>()
            val snapshot = synchronized(lock) {
                val all = store.read()
                val foreign = all.filter { it.userId.isNullOrBlank() || it.userId != uid }
                val own = all.filter { it.userId == uid }
                foreign.forEach { foreignPaths += it.replayLocalPath }
                if (foreign.isNotEmpty() && own.isEmpty()) {
                    store.write(emptyList())
                }
                own
            }
            foreignPaths.forEach { replayFiles.deleteFile(it) }
            if (snapshot.isEmpty()) return@withLock 0

            val remaining = mutableListOf<PendingScoreSubmission>()
            val filesToDelete = mutableListOf<String?>()
            var syncedCount = 0

            for (item in snapshot) {
                val rankResult = runCatching { client.fetchMyGameRankScore(item.gameId) }
                if (rankResult.isSuccess) {
                    val currentBest = rankResult.getOrNull()
                    if (currentBest != null && item.score < currentBest) {
                        filesToDelete += item.replayLocalPath
                        continue
                    }
                }

                val localPath = item.replayLocalPath?.takeIf { it.isNotBlank() }
                if (localPath == null || !replayFiles.existsAndNonEmpty(localPath)) {
                    filesToDelete += item.replayLocalPath
                    continue
                }

                val replaySha256 = replayFiles.sha256Hex(localPath)
                    ?.lowercase()
                    ?.takeIf { SHA256_HEX_REGEX.matches(it) }
                if (replaySha256 == null) {
                    filesToDelete += localPath
                    continue
                }

                val uploaded = runCatching { client.uploadReplay(item.gameId, localPath) }.getOrNull()
                if (uploaded == null) {
                    // Fallo transitorio subiendo replay: conservar en cola y jamás enviar con replayPath = null.
                    remaining.add(item)
                    continue
                }

                val effectiveSha256 = replaySha256
                val result = runCatching {
                    client.submitHighScore(
                        gameId = item.gameId,
                        score = item.score,
                        romSha256 = item.romSha256,
                        creditsUsed = item.creditsUsed,
                        isHardcore = item.isHardcore,
                        replayPath = uploaded.path,
                        replaySizeBytes = uploaded.sizeBytes,
                        replaySha256 = effectiveSha256
                    )
                }
                val dto = result.getOrNull()
                if (result.isSuccess && dto != null) {
                    if (dto.accepted) {
                        syncedCount++
                        filesToDelete += localPath
                    } else if (isPermanentRejection(dto.reason)) {
                        filesToDelete += localPath
                    } else {
                        remaining.add(item)
                    }
                } else {
                    remaining.add(item)
                }
            }

            synchronized(lock) {
                val latestAll = store.read()
                val latestForeign = latestAll.filter { it.userId.isNullOrBlank() || it.userId != uid }
                latestForeign.forEach { filesToDelete += it.replayLocalPath }

                val latestOwn = latestAll.filter { it.userId == uid }
                val snapshotByGameId = snapshot.associateBy { it.gameId }
                val mergedByGameId = LinkedHashMap<String, PendingScoreSubmission>()
                for (rem in remaining) {
                    mergedByGameId[rem.gameId] = rem
                }

                for (latestItem in latestOwn) {
                    val snapItem = snapshotByGameId[latestItem.gameId]
                    val isNewOrSuperseded = snapItem == null ||
                        latestItem.score > snapItem.score ||
                        (latestItem.score == snapItem.score && latestItem.queuedAtMs > snapItem.queuedAtMs)
                    if (isNewOrSuperseded) {
                        val existing = mergedByGameId[latestItem.gameId]
                        if (existing == null ||
                            latestItem.score > existing.score ||
                            (latestItem.score == existing.score && latestItem.queuedAtMs > existing.queuedAtMs)
                        ) {
                            if (existing != null &&
                                !existing.replayLocalPath.isNullOrBlank() &&
                                existing.replayLocalPath != latestItem.replayLocalPath
                            ) {
                                filesToDelete += existing.replayLocalPath
                            }
                            mergedByGameId[latestItem.gameId] = latestItem
                        }
                    }
                }

                store.write(mergedByGameId.values.toList())
            }

            filesToDelete.forEach { replayFiles.deleteFile(it) }
            syncedCount
        }
    }

    companion object {
        private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

        val permanentRejectionReasons = setOf(
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
    }
}

private class SharedPreferencesScoreQueueStore(context: Context) : ScoreQueueStore {
    private val appCtx = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): List<PendingScoreSubmission> {
        val raw = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingScoreSubmission>>(raw) }
            .getOrDefault(emptyList())
    }

    override fun write(entries: List<PendingScoreSubmission>) {
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_QUEUE).apply()
        } else {
            prefs.edit().putString(KEY_QUEUE, json.encodeToString(entries)).apply()
        }
    }

    override fun clear() {
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "balddog_pending_scores"
        private const val KEY_QUEUE = "queue_json"
    }
}

internal class AndroidReplayFileHandler(context: Context) : ReplayFileHandler {
    private val pendingDir = File(context.applicationContext.filesDir, "pending_replays")

    override fun existsAndNonEmpty(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val f = File(path)
        return f.exists() && f.isFile && f.length() > 0L
    }

    override fun sha256Hex(path: String): String? {
        val f = File(path)
        if (!f.exists() || !f.isFile || f.length() <= 0L) return null
        return runCatching {
            val bytes = f.readBytes()
            if (bytes.isEmpty()) return null
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    override fun deleteFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    override fun clearAllReplayFiles() {
        runCatching {
            if (pendingDir.exists()) {
                pendingDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        }
    }
}

private class ArcadeScoreSyncClient(private val repo: ArcadeRepository) : ScoreSyncClient {
    override fun currentUserId(): String? = repo.currentUserId()

    override suspend fun fetchMyGameRankScore(gameId: String): Long? {
        return repo.fetchMyGameRank(gameId)?.score
    }

    override suspend fun uploadReplay(gameId: String, localPath: String): UploadedReplay? {
        val uploaded = repo.uploadReplay(gameId, File(localPath)) ?: return null
        return UploadedReplay(
            path = uploaded.first,
            sizeBytes = uploaded.second,
            sha256 = uploaded.third
        )
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
        return repo.submitHighScore(
            gameId = gameId,
            score = score,
            romSha256 = romSha256,
            creditsUsed = creditsUsed,
            isHardcore = isHardcore,
            replayPath = replayPath,
            replaySizeBytes = replaySizeBytes,
            replaySha256 = replaySha256
        )
    }
}

/**
 * Cola persistente local para no perder récords si falla la red al terminar una partida.
 * Conserva únicamente el puntaje más alto pendiente por cada `(userId, gameId)`.
 */
object PendingScoreQueue {
    @Volatile
    private var engineInstance: PendingScoreQueueEngine? = null
    private val initLock = Any()

    private fun engine(context: Context): PendingScoreQueueEngine {
        return engineInstance ?: synchronized(initLock) {
            engineInstance ?: PendingScoreQueueEngine(
                store = SharedPreferencesScoreQueueStore(context),
                replayFiles = AndroidReplayFileHandler(context)
            ).also { engineInstance = it }
        }
    }

    fun isPermanentRejection(reason: String?): Boolean {
        return reason != null && reason in PendingScoreQueueEngine.permanentRejectionReasons
    }

    fun pendingCount(context: Context, currentUserId: String? = null): Int {
        return engine(context).pendingCount(currentUserId)
    }

    fun enqueue(context: Context, entry: PendingScoreSubmission) {
        engine(context).enqueue(entry)
    }

    fun clear(context: Context) {
        engine(context).clear()
    }

    fun clearAll(context: Context) {
        clear(context)
    }

    suspend fun flushAll(context: Context, repo: ArcadeRepository): Int {
        return engine(context).flushAll(ArcadeScoreSyncClient(repo))
    }
}
