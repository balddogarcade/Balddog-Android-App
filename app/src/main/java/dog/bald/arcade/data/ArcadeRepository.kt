package dog.bald.arcade.data

import android.content.Context
import dog.bald.arcade.BuildConfig
import dog.bald.arcade.diagnostics.CrashlyticsHelper
import dog.bald.arcade.diagnostics.PerformanceHelper
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

class ArcadeRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val client = SupabaseClientProvider.get(appContext)

    val sessionStatus: StateFlow<SessionStatus>
        get() = client.auth.sessionStatus

    fun isLoggedIn(): Boolean = client.auth.currentSessionOrNull() != null

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    fun currentUserEmail(): String? = client.auth.currentUserOrNull()?.email

    suspend fun restoreSession(): Boolean =
        runCatching {
            client.auth.loadFromStorage()
            client.auth.currentSessionOrNull() != null
        }.getOrDefault(false)

    suspend fun refreshSessionIfNeeded() {
        if (client.auth.sessionStatus.value is SessionStatus.RefreshFailure) {
            runCatching { client.auth.refreshCurrentSession() }
        }
    }

    suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signInWithGoogle() {
        client.auth.signInWith(io.github.jan.supabase.auth.providers.Google)
    }

    suspend fun resetPasswordForEmail(email: String) {
        client.auth.resetPasswordForEmail(
            email = email.trim(),
            redirectUrl = "dog.bald.arcade://reset-password",
        )
    }

    suspend fun updatePassword(newPassword: String) {
        client.auth.updateUser {
            password = newPassword
        }
    }

    suspend fun resendVerificationEmail(email: String) {
        client.auth.resendEmail(
            type = io.github.jan.supabase.auth.OtpType.Email.SIGNUP,
            email = email.trim(),
        )
    }

    fun handleAuthDeeplink(intent: android.content.Intent) {
        client.handleDeeplinks(intent)
    }

    suspend fun signUpWithEmail(
        email: String,
        password: String,
        username: String,
    ) {
        val cleanUsername = username.trim()
        require(cleanUsername.matches(Regex("^[a-zA-Z0-9_]{3,20}$"))) {
            "El username debe tener entre 3 y 20 caracteres (letras, números y _)"
        }
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data =
                buildJsonObject {
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
        ProfileCache.clear(appContext)
        client.auth.signOut()
    }

    suspend fun fetchGames(): List<GameDto> =
        PerformanceHelper.measureTraceSuspend("catalog_fetch_trace") {
            client.from("games").select().decodeList<GameDto>().also {
                dog.bald.arcade.ui.components.CoverUrlRegistry
                    .registerAll(it)
            }
        }

    suspend fun fetchBios(): List<GameBiosDto> = client.from("game_bios").select().decodeList<GameBiosDto>()

    suspend fun fetchOpenRooms(): List<RoomDto> =
        client
            .from("rooms")
            .select {
                filter {
                    eq("status", "waiting")
                    eq("is_private", false)
                }
            }.decodeList<RoomDto>()

    suspend fun fetchRoomById(roomId: String): RoomDto? =
        client
            .from("rooms")
            .select {
                filter {
                    eq("id", roomId)
                }
            }.decodeSingleOrNull<RoomDto>()

    suspend fun fetchRoomByCodeOrId(codeOrId: String): RoomDto? {
        val clean = codeOrId.trim().removePrefix("#")
        if (clean.isEmpty()) return null
        val params =
            buildJsonObject {
                put("p_code", clean)
            }
        return client.postgrest
            .rpc("find_room_by_code", params)
            .decodeList<RoomDto>()
            .firstOrNull()
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
        voiceEndpoint: String? = null,
        avatarSeed: String? = null,
    ): RoomDto {
        val uid = currentUserId() ?: throw IllegalStateException("Usuario no autenticado")
        val trace = PerformanceHelper.startTrace("room_create_trace")
        var lastException: Throwable? = null
        var outcome = "error"
        try {
            for (attempt in 1..5) {
                val code = (1000..9999).random(Random).toString() + ('A'..'Z').random(Random)
                val initialSlots =
                    mapOf(
                        "0" to
                            RoomSlotInfo(
                                userId = uid,
                                username = username,
                                endpoint = endpoint,
                                voiceEndpoint = voiceEndpoint?.ifBlank { null },
                                ready = true,
                                avatarSeed = avatarSeed?.ifBlank { null },
                            ),
                    )
                val payload =
                    buildJsonObject {
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
                try {
                    val created =
                        client
                            .from("rooms")
                            .insert(payload) {
                                select()
                            }.decodeSingle<RoomDto>()
                    outcome = "success"
                    return created
                } catch (t: Throwable) {
                    lastException = t
                }
            }
            lastException?.let { CrashlyticsHelper.recordNonFatal(it) }
            throw lastException ?: IllegalStateException("No se pudo crear la sala")
        } finally {
            PerformanceHelper.stopTrace(
                trace,
                attributes = mapOf("mode" to mode, "outcome" to outcome),
            )
        }
        throw lastException ?: IllegalStateException("No se pudo crear la sala")
    }

    suspend fun updateRoomSlot(
        roomId: String,
        action: String,
        slotIndex: Int? = null,
        ready: Boolean? = null,
        status: String? = null,
        endpoint: String? = null,
        voiceEndpoint: String? = null,
    ): RoomDto {
        val params =
            buildJsonObject {
                put("p_room_id", roomId)
                put("p_action", action)
                slotIndex?.let { put("p_slot_index", it) }
                ready?.let { put("p_ready", it) }
                status?.let { put("p_status", it) }
                endpoint?.let { put("p_endpoint", it) }
                voiceEndpoint?.let { put("p_voice_endpoint", it) }
            }
        return client.postgrest.rpc("update_room_slot", params).decodeAs<RoomDto>()
    }

    suspend fun deleteRoom(roomId: String) {
        client.from("rooms").delete {
            filter { eq("id", roomId) }
        }
    }

    suspend fun touchRoomHeartbeat(roomId: String) {
        updateRoomSlot(roomId = roomId, action = "status")
    }

    suspend fun banRoomPlayer(
        roomId: String,
        targetUserId: String,
    ): RoomDto {
        val params =
            buildJsonObject {
                put("p_room_id", roomId)
                put("p_target_user_id", targetUserId)
            }
        return client.postgrest.rpc("ban_room_player", params).decodeAs<RoomDto>()
    }

    suspend fun findOrEnqueueMatch(
        gameSlug: String,
        endpoint: String,
        voiceEndpoint: String? = null,
    ): MatchmakingResultDto {
        val trace = PerformanceHelper.startTrace("matchmaking_enqueue_rpc")
        return try {
            val params =
                buildJsonObject {
                    put("p_game_slug", gameSlug)
                    put("p_endpoint", endpoint)
                    voiceEndpoint?.ifBlank { null }?.let { put("p_voice_endpoint", it) }
                }
            val result = client.postgrest.rpc("find_or_enqueue_match", params).decodeAs<MatchmakingResultDto>()
            PerformanceHelper.stopTrace(
                trace,
                mapOf(
                    "rom_slug" to gameSlug,
                    "result_status" to result.status,
                    "source" to result.source,
                ),
            )
            result
        } catch (t: Throwable) {
            PerformanceHelper.stopTrace(
                trace,
                mapOf(
                    "rom_slug" to gameSlug,
                    "result_status" to "error",
                ),
            )
            CrashlyticsHelper.logMatchmakingError(gameSlug, "find_or_enqueue", t)
            throw t
        }
    }

    suspend fun touchMatchmakingHeartbeat() {
        runCatching {
            client.postgrest.rpc("touch_matchmaking_heartbeat")
        }.onFailure { t ->
            CrashlyticsHelper.logMatchmakingError("queue", "heartbeat", t)
        }
    }

    suspend fun cancelMatchmaking() {
        runCatching {
            client.postgrest.rpc("cancel_matchmaking")
        }.onFailure { t ->
            CrashlyticsHelper.logMatchmakingError("queue", "cancel", t)
        }
    }

    suspend fun fetchMatchmakingStats(): MatchmakingStatsDto =
        PerformanceHelper.measureTrace("matchmaking_stats_fetch") {
            client.postgrest.rpc("get_matchmaking_stats").decodeAs<MatchmakingStatsDto>()
        }

    suspend fun fetchMyMatchmakingRow(): MatchmakingQueueRowDto? {
        val uid = currentUserId() ?: return null
        return client
            .from("matchmaking_queue")
            .select {
                filter { eq("user_id", uid) }
                limit(1)
            }.decodeList<MatchmakingQueueRowDto>()
            .firstOrNull()
    }

    suspend fun observeMatchmakingMatch(userId: String): Flow<MatchmakingQueueRowDto> {
        val channelName = "mm_queue_$userId"
        val channel = client.realtime.channel(channelName)
        val changeFlow =
            channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                table = "matchmaking_queue"
                filter("user_id", FilterOperator.EQ, userId)
            }
        channel.subscribe()
        return changeFlow
            .mapNotNull { action ->
                runCatching { action.decodeRecord<MatchmakingQueueRowDto>() }.getOrNull()
            }.filter { row ->
                row.status == "matched" && !row.roomId.isNullOrBlank()
            }.onCompletion {
                runCatching { channel.unsubscribe() }
            }
    }

    suspend fun fetchRoomMessages(roomId: String): List<RoomMessageDto> =
        client
            .from("room_messages")
            .select {
                filter { eq("room_id", roomId) }
                order("created_at", Order.ASCENDING)
            }.decodeList<RoomMessageDto>()

    suspend fun sendRoomMessage(
        roomId: String,
        message: String,
    ): RoomMessageDto {
        val clean = message.trim().take(240)
        require(clean.isNotEmpty()) { "El mensaje no puede estar vacío" }
        val params =
            buildJsonObject {
                put("p_room_id", roomId)
                put("p_content", clean)
            }
        return client.postgrest.rpc("send_room_message", params).decodeAs<RoomMessageDto>()
    }

    suspend fun uploadReplay(
        gameId: String,
        file: File,
    ): Triple<String, Int, String>? {
        val uid = currentUserId() ?: return null
        val token = client.auth.currentAccessTokenOrNull() ?: return null
        if (!file.exists() || file.length() <= 0L) return null

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        if (bytes.isEmpty()) return null
        val sha256 =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        val objectPath = "$gameId/$uid/${UUID.randomUUID()}.bsv.gz"
        val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/storage/v1/object/replays/$objectPath"

        return withContext(Dispatchers.IO) {
            val trace = PerformanceHelper.startTrace("replay_upload_trace")
            var outcome = "error"
            val conn =
                (URL(endpoint).openConnection() as HttpURLConnection).apply {
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
                    outcome = "success"
                    Triple(objectPath, bytes.size, sha256)
                } else {
                    val errBody =
                        runCatching {
                            conn.errorStream?.bufferedReader()?.use { it.readText() }
                        }.getOrNull().orEmpty()
                    android.util.Log.w("BaldDogReplay", "uploadReplay failed HTTP $code: $errBody")
                    CrashlyticsHelper.log("uploadReplay failed HTTP $code for game=$gameId")
                    null
                }
            } catch (t: Throwable) {
                CrashlyticsHelper.recordNonFatal(t)
                null
            } finally {
                conn.disconnect()
                PerformanceHelper.stopTrace(
                    trace,
                    attributes = mapOf("game_id" to gameId, "outcome" to outcome),
                    metrics = mapOf("bytes_uploaded" to bytes.size.toLong()),
                )
            }
        }
    }

    suspend fun downloadReplay(
        replayPath: String,
        destFile: File,
    ): File {
        if (destFile.exists() && destFile.length() > 0L) {
            return destFile
        }
        val cleanPath = replayPath.trimStart('/')
        val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/storage/v1/object/public/replays/$cleanPath"
        return withContext(Dispatchers.IO) {
            destFile.parentFile?.mkdirs()
            val conn =
                (URL(endpoint).openConnection() as HttpURLConnection).apply {
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
        val params =
            buildJsonObject {
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
        val params =
            buildJsonObject {
                put("p_game_id", gameId)
                put("p_limit", limit)
                put("p_offset", offset)
            }
        return client.postgrest.rpc("get_game_leaderboard", params).decodeList<LeaderboardEntryDto>()
    }

    suspend fun fetchMyGameRank(gameId: String): LeaderboardEntryDto? {
        if (currentUserId() == null) return null
        val params =
            buildJsonObject {
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
        val validationErr = SocialValidation.validateUsername(sanitized)
        require(validationErr == null) { validationErr ?: "Nombre de usuario inválido" }
        val current = fetchMyProfile()
        return updateMyProfile(
            username = sanitized,
            bio = current?.bio,
            countryCode = current?.countryCode,
            avatarSeed = current?.avatarSeed,
            isPrivate = current?.isPrivate ?: false,
            socialLinks = current?.socialLinks ?: SocialLinksDto(),
        )
    }

    suspend fun fetchMyProfile(): ProfileDto? {
        val uid = currentUserId() ?: return null
        val existing =
            client
                .from("profiles")
                .select {
                    filter {
                        eq("id", uid)
                    }
                }.decodeSingleOrNull<ProfileDto>()
        if (existing != null) {
            ProfileCache.save(appContext, uid, existing)
            if (shouldTriggerWelcomeEmail(existing)) {
                triggerWelcomeEmailAsync()
            }
            return existing
        }

        val user = client.auth.currentUserOrNull()
        val rawMetaName =
            user
                ?.userMetadata
                ?.get("username")
                ?.toString()
                ?.trim('"')
                ?.takeIf { it.isNotBlank() }
                ?: "player_${uid.replace("-", "").take(8)}"
        val metaName =
            if (SocialValidation.validateUsername(rawMetaName) == null) {
                rawMetaName
            } else {
                "player_${uid.replace("-", "").take(8)}"
            }
        val avatarSeed = "av_${uid.replace("-", "").take(8)}"
        val payload =
            buildJsonObject {
                put("id", uid)
                put("username", metaName)
                put("avatar_seed", avatarSeed)
                put("is_anonymous", false)
            }
        val created =
            runCatching {
                client.from("profiles").insert(payload) { select() }.decodeSingle<ProfileDto>()
            }.getOrNull()
        if (created != null) {
            ProfileCache.save(appContext, uid, created)
        }
        if (shouldTriggerWelcomeEmail(created)) {
            triggerWelcomeEmailAsync()
        }
        return created
    }

    private fun triggerWelcomeEmailAsync() {
        val accessToken = client.auth.currentSessionOrNull()?.accessToken ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val url = java.net.URL("${dog.bald.arcade.BuildConfig.SUPABASE_URL}/functions/v1/send-welcome-email")
                val conn =
                    (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $accessToken")
                        setRequestProperty("apikey", dog.bald.arcade.BuildConfig.SUPABASE_ANON_KEY)
                        setRequestProperty("Content-Type", "application/json")
                    }
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) {
                    dog.bald.arcade.diagnostics.CrashlyticsHelper.recordNonFatal(
                        IllegalStateException("send-welcome-email HTTP $code"),
                    )
                }
            }.onFailure { t ->
                dog.bald.arcade.diagnostics.CrashlyticsHelper
                    .recordNonFatal(t)
            }
        }
    }

    companion object {
        internal fun shouldTriggerWelcomeEmail(profile: ProfileDto?): Boolean = profile != null && !profile.welcomeEmailSent
    }

    suspend fun incrementGameDownload(gameId: String) {
        if (currentUserId() == null) return
        val params =
            buildJsonObject {
                put("p_game_id", gameId)
            }
        client.postgrest.rpc("increment_game_download", params)
    }

    suspend fun recordPlaySession(
        gameId: String,
        seconds: Long,
        isNewSession: Boolean,
    ) {
        if (currentUserId() == null || gameId.isBlank() || seconds <= 0L) return
        val params =
            buildJsonObject {
                put("p_game_id", gameId)
                put("p_seconds", seconds)
                put("p_is_new_session", isNewSession)
            }
        client.postgrest.rpc("record_play_session", params)
    }

    suspend fun fetchMyGameStats(): List<UserGameStatDto> {
        val uid = currentUserId() ?: return emptyList()
        return client
            .from("user_game_stats")
            .select {
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
        val effectivePlayers =
            if (players.isEmpty()) {
                listOf(MatchHistoryPlayerDto(userId = uid, username = "Jugador", slot = 0))
            } else {
                players
            }
        val playersJsonArray =
            kotlinx.serialization.json.buildJsonArray {
                effectivePlayers.forEach { p ->
                    add(
                        buildJsonObject {
                            put("user_id", p.userId)
                            put("username", p.username)
                            put("slot", p.slot)
                        },
                    )
                }
            }
        val participantIdsArray =
            kotlinx.serialization.json.buildJsonArray {
                effectivePlayers.map { it.userId }.distinct().forEach { pid ->
                    add(kotlinx.serialization.json.JsonPrimitive(pid))
                }
            }
        val params =
            buildJsonObject {
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
        val params =
            buildJsonObject {
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
        socialLinks: SocialLinksDto,
    ): ProfileDto {
        val linksJson =
            buildJsonObject {
                socialLinks.x
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("x", it) }
                socialLinks.instagram
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("instagram", it) }
                socialLinks.youtube
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("youtube", it) }
                socialLinks.twitch
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("twitch", it) }
                socialLinks.discord
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("discord", it) }
                socialLinks.github
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("github", it) }
                socialLinks.website
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { put("website", it) }
            }
        val params =
            buildJsonObject {
                put("p_username", username.trim())
                if (!bio.isNullOrBlank()) put("p_bio", bio.trim()) else put("p_bio", kotlinx.serialization.json.JsonNull)
                if (!countryCode.isNullOrBlank()) {
                    put(
                        "p_country_code",
                        countryCode.trim().uppercase(),
                    )
                } else {
                    put("p_country_code", kotlinx.serialization.json.JsonNull)
                }
                if (!avatarSeed.isNullOrBlank()) {
                    put(
                        "p_avatar_seed",
                        avatarSeed.trim(),
                    )
                } else {
                    put("p_avatar_seed", kotlinx.serialization.json.JsonNull)
                }
                put("p_is_private", isPrivate)
                put("p_social_links", linksJson)
            }
        val updated = client.postgrest.rpc("update_my_profile", params).decodeAs<ProfileDto>()
        ProfileCache.save(appContext, currentUserId(), updated)
        return updated
    }

    suspend fun fetchPublicProfile(userId: String): PublicProfileDto? {
        if (currentUserId() == null || userId.isBlank()) return null
        val params =
            buildJsonObject {
                put("p_user_id", userId)
            }
        return client.postgrest.rpc("get_public_profile", params).decodeAsOrNull<PublicProfileDto>()
    }

    suspend fun followUser(targetUserId: String) {
        if (currentUserId() == null || targetUserId.isBlank()) return
        val params =
            buildJsonObject {
                put("p_target_user_id", targetUserId)
            }
        client.postgrest.rpc("follow_user", params)
    }

    suspend fun unfollowUser(targetUserId: String) {
        if (currentUserId() == null || targetUserId.isBlank()) return
        val params =
            buildJsonObject {
                put("p_target_user_id", targetUserId)
            }
        client.postgrest.rpc("unfollow_user", params)
    }

    suspend fun fetchFollowList(
        userId: String,
        direction: String,
        limit: Int = 50,
        offset: Int = 0,
    ): List<FollowUserDto> {
        if (currentUserId() == null || userId.isBlank()) return emptyList()
        val params =
            buildJsonObject {
                put("p_user_id", userId)
                put("p_direction", direction)
                put("p_limit", limit)
                put("p_offset", offset)
            }
        return client.postgrest.rpc("get_follow_list", params).decodeList<FollowUserDto>()
    }

    suspend fun fetchMyFollowingIds(): Set<String> {
        val uid = currentUserId() ?: return emptySet()
        val result = mutableSetOf<String>()
        val pageSize = 100
        var offset = 0
        while (true) {
            val batch =
                runCatching {
                    fetchFollowList(userId = uid, direction = "following", limit = pageSize, offset = offset)
                }.getOrNull().orEmpty()
            if (batch.isEmpty()) break
            result.addAll(batch.map { it.userId })
            if (batch.size < pageSize) break
            offset += pageSize
        }
        return result
    }

    suspend fun fetchGlobalLeaderboard(
        onlyFollowing: Boolean = false,
        search: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<GlobalLeaderboardEntryDto> {
        if (currentUserId() == null) return emptyList()
        val params =
            buildJsonObject {
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
        offset: Int = 0,
    ): List<GameLeaderboardV2EntryDto> {
        if (currentUserId() == null || gameId.isBlank()) return emptyList()
        val params =
            buildJsonObject {
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

    suspend fun deleteAccount() {
        val jwt =
            client.auth.currentSessionOrNull()?.accessToken
                ?: throw IllegalStateException("No hay sesión activa")
        val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/delete-account"
        withContext(Dispatchers.IO) {
            val conn =
                (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $jwt")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            conn.outputStream.use { it.write("{}".toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                throw IllegalStateException("No se pudo eliminar la cuenta (HTTP $code)")
            }
        }
        runCatching { client.auth.signOut() }
    }
}
