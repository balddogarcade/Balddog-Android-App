package dog.bald.arcade.data

import android.content.Context
import dog.bald.arcade.BuildConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
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

    suspend fun signInWithEmail(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUpWithEmail(email: String, password: String, username: String) {
        val cleanUsername = username.trim()
        require(cleanUsername.matches(Regex("^[a-zA-Z0-9_]{3,20}$"))) {
            "El username debe tener entre 3 y 20 caracteres (letras, números y _)"
        }
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("username", cleanUsername)
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

    suspend fun uploadReplay(gameId: String, file: File): Triple<String, Int, String>? {
        val uid = currentUserId() ?: return null
        val token = client.auth.currentAccessTokenOrNull() ?: return null
        if (!file.exists() || file.length() <= 0L) return null

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        if (bytes.isEmpty()) return null
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val objectPath = "$gameId/$uid/${UUID.randomUUID()}.bsv.gz"
        val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/storage/v1/object/replays/$objectPath"

        return withContext(Dispatchers.IO) {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Content-Type", "application/gzip")
            }
            try {
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Triple(objectPath, bytes.size, sha256)
                } else {
                    val errBody = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    android.util.Log.w("BaldDogReplay", "uploadReplay failed HTTP $code: $errBody")
                    null
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun downloadReplay(replayPath: String, destFile: File): File {
        val cleanPath = replayPath.trimStart('/')
        val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/storage/v1/object/public/replays/$cleanPath"
        return withContext(Dispatchers.IO) {
            destFile.parentFile?.mkdirs()
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("No se pudo descargar el replay (HTTP $code)")
                }
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun submitHighScore(
        gameId: String,
        score: Long,
        romSha256: String,
        creditsUsed: Int = 1,
        isHardcore: Boolean = true,
        replayPath: String? = null,
        replaySizeBytes: Int? = null,
        replaySha256: String? = null,
    ): SubmitScoreResultDto? {
        if (currentUserId() == null || gameId.isBlank() || score <= 0L) return null
        val params = buildJsonObject {
            put("p_game_id", gameId)
            put("p_score", score)
            put("p_rom_sha256", romSha256)
            put("p_credits_used", creditsUsed)
            put("p_is_hardcore", isHardcore)
            if (!replayPath.isNullOrBlank()) {
                put("p_replay_path", replayPath)
                replaySizeBytes?.let { put("p_replay_size_bytes", it) }
                replaySha256?.takeIf { it.isNotBlank() }?.let { put("p_replay_sha256", it.lowercase()) }
            }
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

    suspend fun fetchMyHighScores(): List<MyHighScoreDto> {
        if (currentUserId() == null) return emptyList()
        return client.postgrest.rpc("get_my_high_scores").decodeList<MyHighScoreDto>()
    }

    suspend fun updateUsername(newUsername: String): ProfileDto {
        val uid = currentUserId() ?: error("Sesión no iniciada")
        val sanitized = newUsername.trim()
        require(sanitized.length in 3..24) {
            "El nombre debe tener entre 3 y 24 caracteres"
        }
        require(sanitized.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
            "Solo se permiten letras, números, '_', '-' y '.'"
        }
        val payload = buildJsonObject {
            put("username", sanitized)
        }
        return client.from("profiles").update(payload) {
            filter { eq("id", uid) }
            select()
        }.decodeSingle<ProfileDto>()
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

    suspend fun recordMatchHistory(
        clientSessionId: String,
        gameId: String,
        mode: String,
        roomId: String? = null,
        isPrivate: Boolean = false,
        players: List<MatchHistoryPlayerDto>,
        startedAtIso: String,
        endedAtIso: String,
        durationSeconds: Int,
        score: Long? = null,
    ) {
        val uid = currentUserId() ?: return
        if (isPrivate || gameId.isBlank() || clientSessionId.isBlank()) return
        val effectivePlayers = if (players.isEmpty()) {
            listOf(MatchHistoryPlayerDto(userId = uid, username = "Jugador", slot = 0))
        } else {
            players
        }
        val playersJsonArray = kotlinx.serialization.json.buildJsonArray {
            effectivePlayers.forEach { p ->
                add(
                    buildJsonObject {
                        put("user_id", p.userId)
                        put("username", p.username)
                        put("slot", p.slot)
                    }
                )
            }
        }
        val participantIdsArray = kotlinx.serialization.json.buildJsonArray {
            effectivePlayers.map { it.userId }.distinct().forEach { pid ->
                add(kotlinx.serialization.json.JsonPrimitive(pid))
            }
        }
        val params = buildJsonObject {
            put("p_client_session_id", clientSessionId)
            put("p_game_id", gameId)
            put("p_mode", mode)
            if (!roomId.isNullOrBlank()) put("p_room_id", roomId)
            put("p_is_private", false)
            put("p_players_json", playersJsonArray)
            put("p_participant_ids", participantIdsArray)
            put("p_started_at", startedAtIso)
            put("p_ended_at", endedAtIso)
            put("p_duration_seconds", durationSeconds.coerceAtLeast(0))
            if (score != null && score > 0L) put("p_score", score)
        }
        client.postgrest.rpc("record_match_history", params)
    }

    suspend fun fetchMyMatchHistory(
        limit: Int = 50,
        offset: Int = 0,
    ): List<MatchHistoryDto> {
        if (currentUserId() == null) return emptyList()
        val params = buildJsonObject {
            put("p_limit", limit)
            put("p_offset", offset)
        }
        return client.postgrest.rpc("get_my_match_history", params).decodeList<MatchHistoryDto>()
    }

    suspend fun updateMyProfile(
        username: String,
        bio: String?,
        countryCode: String?,
        avatarSeed: String?,
        isPrivate: Boolean,
        socialLinks: SocialLinksDto
    ): ProfileDto {
        val linksJson = buildJsonObject {
            socialLinks.x?.trim()?.takeIf { it.isNotEmpty() }?.let { put("x", it) }
            socialLinks.instagram?.trim()?.takeIf { it.isNotEmpty() }?.let { put("instagram", it) }
            socialLinks.youtube?.trim()?.takeIf { it.isNotEmpty() }?.let { put("youtube", it) }
            socialLinks.twitch?.trim()?.takeIf { it.isNotEmpty() }?.let { put("twitch", it) }
            socialLinks.discord?.trim()?.takeIf { it.isNotEmpty() }?.let { put("discord", it) }
            socialLinks.github?.trim()?.takeIf { it.isNotEmpty() }?.let { put("github", it) }
            socialLinks.website?.trim()?.takeIf { it.isNotEmpty() }?.let { put("website", it) }
        }
        val params = buildJsonObject {
            put("p_username", username.trim())
            if (!bio.isNullOrBlank()) put("p_bio", bio.trim()) else put("p_bio", kotlinx.serialization.json.JsonNull)
            if (!countryCode.isNullOrBlank()) put("p_country_code", countryCode.trim().uppercase()) else put("p_country_code", kotlinx.serialization.json.JsonNull)
            if (!avatarSeed.isNullOrBlank()) put("p_avatar_seed", avatarSeed.trim()) else put("p_avatar_seed", kotlinx.serialization.json.JsonNull)
            put("p_is_private", isPrivate)
            put("p_social_links", linksJson)
        }
        return client.postgrest.rpc("update_my_profile", params).decodeAs<ProfileDto>()
    }

    suspend fun fetchPublicProfile(userId: String): PublicProfileDto? {
        if (currentUserId() == null || userId.isBlank()) return null
        val params = buildJsonObject {
            put("p_user_id", userId)
        }
        return client.postgrest.rpc("get_public_profile", params).decodeAsOrNull<PublicProfileDto>()
    }

    suspend fun followUser(targetUserId: String) {
        if (currentUserId() == null || targetUserId.isBlank()) return
        val params = buildJsonObject {
            put("p_target_user_id", targetUserId)
        }
        client.postgrest.rpc("follow_user", params)
    }

    suspend fun unfollowUser(targetUserId: String) {
        if (currentUserId() == null || targetUserId.isBlank()) return
        val params = buildJsonObject {
            put("p_target_user_id", targetUserId)
        }
        client.postgrest.rpc("unfollow_user", params)
    }

    suspend fun fetchFollowList(
        userId: String,
        direction: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<FollowUserDto> {
        if (currentUserId() == null || userId.isBlank()) return emptyList()
        val params = buildJsonObject {
            put("p_user_id", userId)
            put("p_direction", direction)
            put("p_limit", limit)
            put("p_offset", offset)
        }
        return client.postgrest.rpc("get_follow_list", params).decodeList<FollowUserDto>()
    }

    suspend fun fetchMyFollowingIds(): Set<String> {
        val uid = currentUserId() ?: return emptySet()
        return runCatching {
            fetchFollowList(userId = uid, direction = "following", limit = 100, offset = 0)
                .map { it.userId }
                .toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun fetchGlobalLeaderboard(
        onlyFollowing: Boolean = false,
        search: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<GlobalLeaderboardEntryDto> {
        if (currentUserId() == null) return emptyList()
        val params = buildJsonObject {
            put("p_only_following", onlyFollowing)
            if (!search.isNullOrBlank()) put("p_search", search.trim())
            put("p_limit", limit)
            put("p_offset", offset)
        }
        return client.postgrest.rpc("get_global_leaderboard", params).decodeList<GlobalLeaderboardEntryDto>()
    }

    suspend fun fetchGameLeaderboardV2(
        gameId: String,
        mode: String = "1cc",
        onlyFollowing: Boolean = false,
        limit: Int = 50,
        offset: Int = 0
    ): List<GameLeaderboardV2EntryDto> {
        if (currentUserId() == null || gameId.isBlank()) return emptyList()
        val params = buildJsonObject {
            put("p_game_id", gameId)
            put("p_mode", mode)
            put("p_only_following", onlyFollowing)
            put("p_limit", limit)
            put("p_offset", offset)
        }
        return client.postgrest.rpc("get_game_leaderboard_v2", params).decodeList<GameLeaderboardV2EntryDto>()
    }

    suspend fun fetchGamesRankingSummary(): List<GameRankingSummaryDto> {
        if (currentUserId() == null) return emptyList()
        return client.postgrest.rpc("get_games_ranking_summary").decodeList<GameRankingSummaryDto>()
    }
}
