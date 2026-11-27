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

    fun logIpfsEvent(
        romSlug: String,
        stage: String,
        gateway: String = "",
        message: String = "",
    ) {
        instance()?.apply {
            setCustomKey("ipfs_rom_slug", romSlug.ifBlank { "unknown" })
            setCustomKey("ipfs_stage", stage.ifBlank { "unknown" })
            val gwInfo = if (gateway.isNotBlank()) " gw=$gateway" else ""
            val msgInfo = if (message.isNotBlank()) " $message" else ""
            log("IPFS [$stage] rom=$romSlug$gwInfo$msgInfo")
        }
    }

    fun logIpfsError(
        romSlug: String,
        gateway: String,
        throwable: Throwable,
    ) {
        instance()?.apply {
            setCustomKey("ipfs_rom_slug", romSlug.ifBlank { "unknown" })
            setCustomKey("ipfs_gateway", gateway.ifBlank { "unknown" })
            log("IPFS error on $romSlug (gw=$gateway): ${throwable.message}")
            recordException(throwable)
        }
    }

    fun logSyncEvent(
        stage: String,
        detail: String = "",
    ) {
        instance()?.apply {
            setCustomKey("sync_stage", stage.ifBlank { "unknown" })
            log("Sync [$stage]${if (detail.isNotBlank()) ": $detail" else ""}")
        }
    }

    fun logSyncError(
        stage: String,
        throwable: Throwable,
    ) {
        instance()?.apply {
            setCustomKey("sync_stage", stage.ifBlank { "unknown" })
            log("Sync error ($stage): ${throwable.message}")
            recordException(throwable)
        }
    }

    fun logAuthEvent(
        method: String,
        outcome: String,
    ) {
        instance()?.apply {
            setCustomKey("auth_method", method.ifBlank { "unknown" })
            setCustomKey("auth_outcome", outcome.ifBlank { "unknown" })
            log("Auth [$method]: $outcome")
        }
    }

    fun logAuthError(
        method: String,
        throwable: Throwable,
    ) {
        instance()?.apply {
            setCustomKey("auth_method", method.ifBlank { "unknown" })
            log("Auth error ($method): ${throwable.message}")
            recordException(throwable)
        }
    }

    fun logMediaExportError(
        type: String,
        romSlug: String,
        throwable: Throwable,
    ) {
        instance()?.apply {
            setCustomKey("export_type", type.ifBlank { "unknown" })
            setCustomKey("export_rom_slug", romSlug.ifBlank { "unknown" })
            log("MediaExport error ($type) on $romSlug: ${throwable.message}")
            recordException(throwable)
        }
    }

    fun logNetEvent(
        component: String,
        detail: String,
    ) {
        instance()?.apply {
            setCustomKey("net_component", component.ifBlank { "unknown" })
            log("Net [$component]: $detail")
        }
    }

    fun logNetError(
        component: String,
        throwable: Throwable,
    ) {
        instance()?.apply {
            setCustomKey("net_component", component.ifBlank { "unknown" })
            log("Net error ($component): ${throwable.message}")
            recordException(throwable)
        }
    }
}
