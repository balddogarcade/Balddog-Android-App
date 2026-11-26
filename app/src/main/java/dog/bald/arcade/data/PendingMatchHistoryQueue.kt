package dog.bald.arcade.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingMatchHistoryEntry(
    val userId: String? = null,
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
    val queuedAtMs: Long = System.currentTimeMillis(),
)

internal interface MatchHistoryQueueStore {
    fun read(): List<PendingMatchHistoryEntry>

    fun write(entries: List<PendingMatchHistoryEntry>)

    fun clear()
}

internal interface MatchHistorySyncClient {
    fun currentUserId(): String?

    suspend fun fetchMyUsername(): String?

    suspend fun recordMatchHistory(
        clientSessionId: String,
        gameId: String,
        mode: String,
        roomId: String?,
        isPrivate: Boolean,
        players: List<MatchHistoryPlayerDto>,
        startedAtIso: String,
        endedAtIso: String,
        durationSeconds: Int,
        score: Long?,
    )
}

internal class PendingMatchHistoryQueueEngine(
    private val store: MatchHistoryQueueStore,
) {
    private val lock = Any()
    private val flushMutex = Mutex()

    fun read(currentUserId: String?): List<PendingMatchHistoryEntry> {
        if (currentUserId.isNullOrBlank()) return emptyList()
        return synchronized(lock) {
            val all = store.read()
            val matching = all.filter { !it.userId.isNullOrBlank() && it.userId == currentUserId }
            if (matching.size != all.size && !flushMutex.isLocked) {
                store.write(matching)
            }
            matching
        }
    }

    fun pendingCount(currentUserId: String?): Int = read(currentUserId).size

    fun enqueue(entry: PendingMatchHistoryEntry) {
        val uid = entry.userId?.takeIf { it.isNotBlank() } ?: return
        if (entry.isPrivate || entry.gameId.isBlank() || entry.clientSessionId.isBlank()) return

        synchronized(lock) {
            val current = store.read().toMutableList()
            val idx = current.indexOfFirst { it.userId == uid && it.clientSessionId == entry.clientSessionId }
            if (idx >= 0) {
                val prev = current[idx]
                val mergedScore = maxOfNullable(prev.score, entry.score)
                val mergedPlayers = if (entry.players.isNotEmpty()) entry.players else prev.players
                current[idx] =
                    entry.copy(
                        userId = uid,
                        players = mergedPlayers,
                        endedAtIso = if (entry.durationSeconds >= prev.durationSeconds) entry.endedAtIso else prev.endedAtIso,
                        durationSeconds = maxOf(prev.durationSeconds, entry.durationSeconds),
                        score = mergedScore,
                        queuedAtMs = maxOf(prev.queuedAtMs, entry.queuedAtMs),
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
            store.write(current)
        }
    }

    fun removeBySessionId(
        clientSessionId: String,
        currentUserId: String? = null,
    ) {
        if (clientSessionId.isBlank()) return
        synchronized(lock) {
            val current = store.read()
            val filtered =
                current.filterNot { entry ->
                    entry.clientSessionId == clientSessionId &&
                        (currentUserId.isNullOrBlank() || entry.userId == currentUserId)
                }
            if (filtered.size != current.size) {
                store.write(filtered)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            store.clear()
        }
    }

    suspend fun flushAll(client: MatchHistorySyncClient): Int {
        val uid = client.currentUserId()?.takeIf { it.isNotBlank() } ?: return 0

        return flushMutex.withLock {
            val snapshot =
                synchronized(lock) {
                    val all = store.read()
                    val own = all.filter { !it.userId.isNullOrBlank() && it.userId == uid }
                    if (all.size != own.size && own.isEmpty()) {
                        store.write(emptyList())
                    }
                    own
                }
            if (snapshot.isEmpty()) return@withLock 0

            val remaining = mutableListOf<PendingMatchHistoryEntry>()
            var syncedCount = 0
            var cachedUsername: String? = null

            for (item in snapshot) {
                if (item.isPrivate) continue
                val resolvedPlayers =
                    if (item.players.isEmpty() && item.mode == "local") {
                        val username =
                            cachedUsername ?: runCatching {
                                client.fetchMyUsername()
                            }.getOrNull()?.also { cachedUsername = it } ?: "Jugador"
                        listOf(MatchHistoryPlayerDto(userId = uid, username = username, slot = 0))
                    } else {
                        item.players
                    }

                val result =
                    runCatching {
                        client.recordMatchHistory(
                            clientSessionId = item.clientSessionId,
                            gameId = item.gameId,
                            mode = item.mode,
                            roomId = item.roomId,
                            isPrivate = item.isPrivate,
                            players = resolvedPlayers,
                            startedAtIso = item.startedAtIso,
                            endedAtIso = item.endedAtIso,
                            durationSeconds = item.durationSeconds,
                            score = item.score,
                        )
                    }

                if (result.isSuccess) {
                    syncedCount++
                } else {
                    val msg = result.exceptionOrNull()?.message.orEmpty()
                    if (!isPermanentError(msg)) {
                        remaining.add(item)
                    }
                }
            }

            synchronized(lock) {
                val latestOwn = store.read().filter { !it.userId.isNullOrBlank() && it.userId == uid }
                val snapshotById = snapshot.associateBy { it.clientSessionId }
                val mergedById = LinkedHashMap<String, PendingMatchHistoryEntry>()
                for (rem in remaining) {
                    mergedById[rem.clientSessionId] = rem
                }
                for (latestItem in latestOwn) {
                    val snapItem = snapshotById[latestItem.clientSessionId]
                    val updatedInFlight = snapItem == null || isNewerOrBetter(latestItem, snapItem)
                    if (updatedInFlight) {
                        mergedById[latestItem.clientSessionId] = latestItem
                    }
                }
                val merged =
                    mergedById.values
                        .sortedBy { it.queuedAtMs }
                        .takeLast(MAX_ENTRIES)
                store.write(merged)
            }
            syncedCount
        }
    }

    private fun isNewerOrBetter(
        latest: PendingMatchHistoryEntry,
        snap: PendingMatchHistoryEntry,
    ): Boolean {
        if (latest.durationSeconds > snap.durationSeconds) return true
        val latestScore = latest.score ?: Long.MIN_VALUE
        val snapScore = snap.score ?: Long.MIN_VALUE
        if (latestScore > snapScore) return true
        if (latest.queuedAtMs > snap.queuedAtMs) return true
        return false
    }

    private fun isPermanentError(msg: String): Boolean = permanentErrorSubstrings.any { msg.contains(it, ignoreCase = true) }

    private fun maxOfNullable(
        a: Long?,
        b: Long?,
    ): Long? =
        when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

    companion object {
        private const val MAX_ENTRIES = 100

        private val permanentErrorSubstrings =
            listOf(
                "Invalid match mode",
                "Invalid mode",
                "not a participant",
                "Game not found",
                "Valid started room not found",
                "Room metadata mismatch",
                "Participant mismatch",
                "Netplay match requires at least 2 participants",
                "Cannot overwrite another session",
                "Registered user required",
                "room_id is required",
            )
    }
}

private class SharedPreferencesMatchHistoryQueueStore(
    context: Context,
) : MatchHistoryQueueStore {
    private val appCtx = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): List<PendingMatchHistoryEntry> {
        val raw =
            appCtx
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_QUEUE, null)
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingMatchHistoryEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    override fun write(entries: List<PendingMatchHistoryEntry>) {
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_QUEUE).apply()
        } else {
            prefs.edit().putString(KEY_QUEUE, json.encodeToString(entries)).apply()
        }
    }

    override fun clear() {
        appCtx
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "balddog_pending_match_history"
        private const val KEY_QUEUE = "queue_json"
    }
}

private class ArcadeMatchHistorySyncClient(
    private val repo: ArcadeRepository,
) : MatchHistorySyncClient {
    override fun currentUserId(): String? = repo.currentUserId()

    override suspend fun fetchMyUsername(): String? = repo.fetchMyProfile()?.username

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
        score: Long?,
    ) {
        repo.recordMatchHistory(
            clientSessionId = clientSessionId,
            gameId = gameId,
            mode = mode,
            roomId = roomId,
            isPrivate = isPrivate,
            players = players,
            startedAtIso = startedAtIso,
            endedAtIso = endedAtIso,
            durationSeconds = durationSeconds,
            score = score,
        )
    }
}

/**
 * Cola persistente local (write-ahead) para historial de partidas offline.
 * Garantiza idempotencia en servidor mediante `clientSessionId`.
 */
object PendingMatchHistoryQueue {
    @Volatile
    private var engineInstance: PendingMatchHistoryQueueEngine? = null
    private val initLock = Any()

    private fun engine(context: Context): PendingMatchHistoryQueueEngine =
        engineInstance ?: synchronized(initLock) {
            engineInstance ?: PendingMatchHistoryQueueEngine(
                store = SharedPreferencesMatchHistoryQueueStore(context),
            ).also { engineInstance = it }
        }

    fun pendingCount(
        context: Context,
        currentUserId: String? = null,
    ): Int = engine(context).pendingCount(currentUserId)

    fun enqueue(
        context: Context,
        entry: PendingMatchHistoryEntry,
    ) {
        engine(context).enqueue(entry)
    }

    fun removeBySessionId(
        context: Context,
        clientSessionId: String,
        currentUserId: String? = null,
    ) {
        engine(context).removeBySessionId(clientSessionId, currentUserId)
    }

    fun clear(context: Context) {
        engine(context).clear()
    }

    fun clearAll(context: Context) {
        clear(context)
    }

    suspend fun flushAll(
        context: Context,
        repo: ArcadeRepository,
    ): Int = engine(context).flushAll(ArcadeMatchHistorySyncClient(repo))
}
