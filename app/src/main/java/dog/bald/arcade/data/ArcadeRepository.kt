package dog.bald.arcade.data

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlin.random.Random

class ArcadeRepository(context: Context) {
    private val client = SupabaseClientProvider.get(context)

    fun isLoggedIn(): Boolean = client.auth.currentSessionOrNull() != null

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    fun currentUserEmail(): String? = client.auth.currentUserOrNull()?.email

    suspend fun restoreSession(): Boolean {
        return runCatching {
            client.auth.loadFromStorage()
            client.auth.currentSessionOrNull() != null
        }.getOrDefault(false)
    }

    suspend fun signInAnonymously() {
        client.auth.signInAnonymously()
    }

    suspend fun signInWithEmail(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUpWithEmail(email: String, password: String, username: String) {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("username", username.trim())
            }
        }
        if (client.auth.currentSessionOrNull() == null) {
            signInWithEmail(email, password)
        }
        if (client.auth.currentSessionOrNull() == null) {
            throw IllegalStateException("Cuenta creada; verifica tu correo o inicia sesión")
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    suspend fun fetchGames(): List<GameDto> {
        return client.from("games").select().decodeList<GameDto>()
    }

    suspend fun fetchBios(): List<GameBiosDto> {
        return client.from("game_bios").select().decodeList<GameBiosDto>()
    }

    suspend fun fetchOpenRooms(): List<RoomDto> {
        return client.from("rooms").select {
            filter {
                eq("status", "waiting")
                eq("is_private", false)
            }
        }.decodeList<RoomDto>()
    }

    suspend fun fetchRoomById(roomId: String): RoomDto? {
        return client.from("rooms").select {
            filter {
                eq("id", roomId)
            }
        }.decodeSingleOrNull<RoomDto>()
    }

    suspend fun fetchRoomByCodeOrId(codeOrId: String): RoomDto? {
        val clean = codeOrId.trim().removePrefix("#")
        if (clean.isEmpty()) return null
        val byCode = client.from("rooms").select {
            filter {
                ilike("code", clean)
                eq("status", "waiting")
            }
        }.decodeList<RoomDto>().firstOrNull()
        if (byCode != null) return byCode
        return runCatching {
            client.from("rooms").select {
                filter {
                    eq("id", clean)
                    eq("status", "waiting")
                }
            }.decodeSingleOrNull<RoomDto>()
        }.getOrNull()
    }

    suspend fun createRoom(
        gameId: String,
        mode: String,
        maxPlayers: Int,
        username: String,
        endpoint: String,
        localIp: String?,
        localPort: Int?,
        isPrivate: Boolean = false,
        voiceEndpoint: String? = null
    ): RoomDto {
        val uid = currentUserId() ?: throw IllegalStateException("Usuario no autenticado")
        val code = (1000..9999).random(Random).toString() + ('A'..'Z').random(Random)
        val initialSlots = mapOf(
            "0" to RoomSlotInfo(
                userId = uid,
                username = username,
                endpoint = endpoint,
                voiceEndpoint = voiceEndpoint?.ifBlank { null },
                ready = true
            )
        )
        val payload = buildJsonObject {
            put("code", code)
            put("host_id", uid)
            put("game_id", gameId)
            put("mode", mode)
            put("status", "waiting")
            put("max_players", maxPlayers.coerceIn(2, 4))
            put("slots_json", Json.encodeToJsonElement(initialSlots))
            put("host_endpoint", endpoint)
            put("is_private", isPrivate)
            if (localIp != null) put("local_ip", localIp)
            if (localPort != null) put("local_port", localPort)
        }
        return client.from("rooms").insert(payload) {
            select()
        }.decodeSingle<RoomDto>()
    }

    suspend fun updateRoomSlots(roomId: String, slots: Map<String, RoomSlotInfo>, status: String? = null) {
        val payload = buildJsonObject {
            put("slots_json", Json.encodeToJsonElement(slots))
            if (status != null) put("status", status)
        }
        client.from("rooms").update(payload) {
            filter { eq("id", roomId) }
        }
    }

    suspend fun closeRoom(roomId: String) {
        val payload = buildJsonObject { put("status", "closed") }
        client.from("rooms").update(payload) {
            filter { eq("id", roomId) }
        }
    }

    suspend fun deleteRoom(roomId: String) {
        client.from("rooms").delete {
            filter { eq("id", roomId) }
        }
    }

    suspend fun touchRoomHeartbeat(roomId: String) {
        val payload = buildJsonObject {
            put("updated_at", java.time.Instant.now().toString())
        }
        client.from("rooms").update(payload) {
            filter { eq("id", roomId) }
        }
    }

    suspend fun fetchRoomMessages(roomId: String): List<RoomMessageDto> {
        return client.from("room_messages").select {
            filter { eq("room_id", roomId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<RoomMessageDto>()
    }

    suspend fun sendRoomMessage(roomId: String, username: String, message: String) {
        val uid = currentUserId() ?: return
        val payload = buildJsonObject {
            put("room_id", roomId)
            put("user_id", uid)
            put("username", username)
            put("message", message.take(240))
        }
        client.from("room_messages").insert(payload)
    }

    suspend fun submitHighScore(
        gameId: String,
        score: Long,
        romSha256: String,
        creditsUsed: Int = 1,
        isHardcore: Boolean = true,
    ): SubmitScoreResultDto? {
        if (currentUserId() == null || gameId.isBlank() || score <= 0L) return null
        val params = buildJsonObject {
            put("p_game_id", gameId)
            put("p_score", score)
            put("p_rom_sha256", romSha256)
            put("p_credits_used", creditsUsed)
            put("p_is_hardcore", isHardcore)
        }
        return client.postgrest.rpc("submit_high_score", params).decodeAs<SubmitScoreResultDto>()
    }

    suspend fun fetchGameLeaderboard(
        gameId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): List<LeaderboardEntryDto> {
        val params = buildJsonObject {
            put("p_game_id", gameId)
            put("p_limit", limit)
            put("p_offset", offset)
        }
        return client.postgrest.rpc("get_game_leaderboard", params).decodeList<LeaderboardEntryDto>()
    }

    suspend fun fetchMyGameRank(gameId: String): LeaderboardEntryDto? {
        if (currentUserId() == null) return null
        val params = buildJsonObject {
            put("p_game_id", gameId)
        }
        return client.postgrest.rpc("get_my_game_rank", params).decodeSingleOrNull<LeaderboardEntryDto>()
    }

    suspend fun fetchMyProfile(): ProfileDto? {
        val uid = currentUserId() ?: return null
        val existing = client.from("profiles").select {
            filter {
                eq("id", uid)
            }
        }.decodeSingleOrNull<ProfileDto>()
        if (existing != null) return existing

        val user = client.auth.currentUserOrNull()
        val metaName = user?.userMetadata?.get("username")?.toString()?.trim('"')
            ?.takeIf { it.isNotBlank() }
            ?: "player_${uid.replace("-", "").take(8)}"
        val payload = buildJsonObject {
            put("id", uid)
            put("username", metaName)
            put("is_anonymous", user?.email.isNullOrBlank())
        }
        return runCatching {
            client.from("profiles").insert(payload) { select() }.decodeSingle<ProfileDto>()
        }.getOrNull()
    }

    suspend fun incrementGameDownload(gameId: String) {
        if (currentUserId() == null) return
        val params = buildJsonObject {
            put("p_game_id", gameId)
        }
        client.postgrest.rpc("increment_game_download", params)
    }

    suspend fun recordPlaySession(gameId: String, seconds: Long, isNewSession: Boolean) {
        if (currentUserId() == null || gameId.isBlank() || seconds <= 0L) return
        val params = buildJsonObject {
            put("p_game_id", gameId)
            put("p_seconds", seconds)
            put("p_is_new_session", isNewSession)
        }
        client.postgrest.rpc("record_play_session", params)
    }

    suspend fun fetchMyGameStats(): List<UserGameStatDto> {
        val uid = currentUserId() ?: return emptyList()
        return client.from("user_game_stats").select {
            filter { eq("user_id", uid) }
            order("play_seconds", Order.DESCENDING)
        }.decodeList<UserGameStatDto>()
    }
}
