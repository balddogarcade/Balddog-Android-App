package dog.bald.arcade.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingScoreSubmission(
    val gameId: String,
    val score: Long,
    val romSha256: String,
    val creditsUsed: Int = 1,
    val isHardcore: Boolean = true,
    val queuedAtMs: Long = System.currentTimeMillis()
)

/**
 * Cola persistente local para no perder récords si falla la red al terminar una partida.
 * Conserva únicamente el puntaje más alto pendiente por cada `gameId`.
 */
object PendingScoreQueue {
    private const val PREFS_NAME = "balddog_pending_scores"
    private const val KEY_QUEUE = "queue_json"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val permanentRejectionReasons = setOf(
        "rom_mismatch",
        "score_exceeds_max",
        "hardcore_required",
        "single_credit_only",
        "game_not_found",
        "invalid_score"
    )

    fun pendingCount(context: Context): Int {
        return synchronized(lock) { readLocked(context).size }
    }

    fun enqueue(context: Context, entry: PendingScoreSubmission) {
        if (entry.gameId.isBlank() || entry.score <= 0L) return
        synchronized(lock) {
            val current = readLocked(context).toMutableList()
            val existingIdx = current.indexOfFirst { it.gameId == entry.gameId }
            if (existingIdx >= 0) {
                if (entry.score > current[existingIdx].score) {
                    current[existingIdx] = entry
                }
            } else {
                current.add(entry)
            }
            writeLocked(context, current)
        }
    }

    suspend fun flushAll(context: Context, repo: ArcadeRepository): Int {
        if (repo.currentUserId() == null) return 0
        val snapshot = synchronized(lock) { readLocked(context) }
        if (snapshot.isEmpty()) return 0

        val remaining = mutableListOf<PendingScoreSubmission>()
        var syncedCount = 0

        for (item in snapshot) {
            val result = runCatching {
                repo.submitHighScore(
                    gameId = item.gameId,
                    score = item.score,
                    romSha256 = item.romSha256,
                    creditsUsed = item.creditsUsed,
                    isHardcore = item.isHardcore
                )
            }
            val dto = result.getOrNull()
            if (result.isSuccess && dto != null) {
                if (dto.accepted || dto.reason in permanentRejectionReasons) {
                    if (dto.accepted) syncedCount++
                } else {
                    remaining.add(item)
                }
            } else {
                remaining.add(item)
            }
        }

        synchronized(lock) {
            writeLocked(context, remaining)
        }
        return syncedCount
    }

    private fun readLocked(context: Context): List<PendingScoreSubmission> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingScoreSubmission>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun writeLocked(context: Context, entries: List<PendingScoreSubmission>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_QUEUE).apply()
        } else {
            prefs.edit().putString(KEY_QUEUE, json.encodeToString(entries)).apply()
        }
    }
}
