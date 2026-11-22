package dog.bald.arcade.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingMatchHistoryEntry(
    val clientSessionId: String,
    val gameId: String,
    val mode: String,
    val roomId: String? = null,
    val isPrivate: Boolean = false,
    val players: List<MatchHistoryPlayerDto> = emptyList(),
    val startedAtIso: String,
    val endedAtIso: String,
    val durationSeconds: Int,
    val score: Long? = null,
    val queuedAtMs: Long = System.currentTimeMillis()
)

/**
 * Cola persistente local (write-ahead) para historial de partidas offline.
 * Garantiza idempotencia en servidor mediante `clientSessionId`.
 */
object PendingMatchHistoryQueue {
    private const val PREFS_NAME = "balddog_pending_match_history"
    private const val KEY_QUEUE = "queue_json"
    private const val MAX_ENTRIES = 100
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun pendingCount(context: Context): Int {
        return synchronized(lock) { readLocked(context).size }
    }

    fun enqueue(context: Context, entry: PendingMatchHistoryEntry) {
        if (entry.isPrivate || entry.gameId.isBlank() || entry.clientSessionId.isBlank()) return
        synchronized(lock) {
            val current = readLocked(context).toMutableList()
            val idx = current.indexOfFirst { it.clientSessionId == entry.clientSessionId }
            if (idx >= 0) {
                val prev = current[idx]
                val mergedScore = maxOfNullable(prev.score, entry.score)
                current[idx] = entry.copy(
                    durationSeconds = maxOf(prev.durationSeconds, entry.durationSeconds),
                    score = mergedScore
                )
            } else {
                current.add(entry)
                if (current.size > MAX_ENTRIES) {
                    current.sortBy { it.queuedAtMs }
                    while (current.size > MAX_ENTRIES) {
                        current.removeAt(0)
                    }
                }
            }
            writeLocked(context, current)
        }
    }

    fun removeBySessionId(context: Context, clientSessionId: String) {
        if (clientSessionId.isBlank()) return
        synchronized(lock) {
            val current = readLocked(context)
            val filtered = current.filterNot { it.clientSessionId == clientSessionId }
            if (filtered.size != current.size) {
                writeLocked(context, filtered)
            }
        }
    }

    suspend fun flushAll(context: Context, repo: ArcadeRepository): Int {
        val uid = repo.currentUserId() ?: return 0
        val snapshot = synchronized(lock) { readLocked(context) }
        if (snapshot.isEmpty()) return 0

        val remaining = mutableListOf<PendingMatchHistoryEntry>()
        var syncedCount = 0
        var cachedUsername: String? = null

        for (item in snapshot) {
            if (item.isPrivate) continue
            val resolvedPlayers = if (item.players.isEmpty() && item.mode == "local") {
                val username = cachedUsername ?: runCatching {
                    repo.fetchMyProfile()?.username
                }.getOrNull()?.also { cachedUsername = it } ?: "Jugador"
                listOf(MatchHistoryPlayerDto(userId = uid, username = username, slot = 0))
            } else {
                item.players
            }

            val result = runCatching {
                repo.recordMatchHistory(
                    clientSessionId = item.clientSessionId,
                    gameId = item.gameId,
                    mode = item.mode,
                    roomId = item.roomId,
                    isPrivate = item.isPrivate,
                    players = resolvedPlayers,
                    startedAtIso = item.startedAtIso,
                    endedAtIso = item.endedAtIso,
                    durationSeconds = item.durationSeconds,
                    score = item.score
                )
            }

            if (result.isSuccess) {
                syncedCount++
            } else {
                val msg = result.exceptionOrNull()?.message.orEmpty()
                val isPermanent = msg.contains("Invalid match mode", ignoreCase = true) ||
                    msg.contains("not a participant", ignoreCase = true)
                if (!isPermanent) {
                    remaining.add(item)
                }
            }
        }

        synchronized(lock) {
            val latest = readLocked(context)
            val snapshotIds = snapshot.map { it.clientSessionId }.toSet()
            val newlyAdded = latest.filterNot { it.clientSessionId in snapshotIds }
            val merged = (remaining + newlyAdded).takeLast(MAX_ENTRIES)
            writeLocked(context, merged)
        }
        return syncedCount
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? {
        return when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }
    }

    private fun readLocked(context: Context): List<PendingMatchHistoryEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingMatchHistoryEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun writeLocked(context: Context, entries: List<PendingMatchHistoryEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_QUEUE).apply()
        } else {
            prefs.edit().putString(KEY_QUEUE, json.encodeToString(entries)).apply()
        }
    }
}
