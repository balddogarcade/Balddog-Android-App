package dog.bald.arcade.diagnostics

import com.google.firebase.crashlytics.FirebaseCrashlytics

object CrashlyticsHelper {
    private fun instance(): FirebaseCrashlytics? = runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()

    fun setGameContext(
        gameId: String,
        gameTitle: String,
        romSlug: String,
        romSha256: String,
        netplayMode: String,
    ) {
        instance()?.apply {
            setCustomKey("game_id", gameId.ifBlank { "unknown" })
            setCustomKey("game_title", gameTitle.ifBlank { "unknown" })
            setCustomKey("rom_name", romSlug.ifBlank { "unknown" })
            setCustomKey("rom_sha256", romSha256.ifBlank { "unknown" })
            setCustomKey("netplay_mode", netplayMode)
        }
    }

    fun setNetplayState(
        state: String,
        roomId: String = "",
        pingMs: UInt = 0u,
    ) {
        instance()?.apply {
            setCustomKey("session_state", state)
            if (roomId.isNotBlank()) {
                setCustomKey("room_id", roomId)
            }
            setCustomKey("netplay_ping_ms", pingMs.toInt())
        }
    }

    fun log(message: String) {
        instance()?.log(message)
    }

    fun recordNonFatal(throwable: Throwable) {
        instance()?.recordException(throwable)
    }

    fun setCurrentGameDetail(
        gameId: String,
        romSlug: String,
    ) {
        instance()?.apply {
            setCustomKey("detail_game_id", gameId.ifBlank { "unknown" })
            setCustomKey("detail_rom_slug", romSlug.ifBlank { "unknown" })
            log("Opened GameDetail for $romSlug ($gameId)")
        }
    }

    fun logGameDetailError(
        romSlug: String,
        throwable: Throwable,
    ) {
        instance()?.apply {
            log("GameDetail error on $romSlug: ${throwable.message}")
            recordException(throwable)
        }
    }

    fun setMatchmakingContext(
        state: String,
        romSlug: String,
    ) {
        instance()?.apply {
            setCustomKey("matchmaking_state", state.ifBlank { "idle" })
            setCustomKey("matchmaking_rom_slug", romSlug.ifBlank { "unknown" })
            log("Matchmaking state=$state rom=$romSlug")
        }
    }

    fun logMatchmakingError(
        romSlug: String,
        stage: String,
        throwable: Throwable,
    ) {
        instance()?.apply {
            setCustomKey("matchmaking_stage", stage.ifBlank { "unknown" })
            log("Matchmaking error ($stage) on $romSlug: ${throwable.message}")
            recordException(throwable)
        }
    }

    fun logDeepLinkEvent(
        stage: String,
        message: String,
    ) {
        instance()?.apply {
            setCustomKey("deeplink_stage", stage.ifBlank { "unknown" })
            log("DeepLink [$stage]: $message")
        }
    }
}
