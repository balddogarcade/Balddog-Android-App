package dog.bald.arcade.diagnostics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsHelper {
    @Volatile
    private var analytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        if (analytics == null) {
            analytics = runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }.getOrNull()
        }
    }

    fun setUserId(userId: String?) {
        runCatching {
            analytics?.setUserId(userId?.takeIf { it.isNotBlank() })
        }
    }

    fun logAuthEvent(
        method: String,
        isSignUp: Boolean,
    ) {
        val eventName = if (isSignUp) FirebaseAnalytics.Event.SIGN_UP else FirebaseAnalytics.Event.LOGIN
        logEvent(eventName) {
            putString(FirebaseAnalytics.Param.METHOD, method.ifBlank { "unknown" })
        }
    }

    fun logGameLaunch(
        gameId: String,
        romSlug: String,
        mode: String,
    ) {
        logEvent("game_launch") {
            putString("game_id", gameId.ifBlank { "unknown" })
            putString("rom_slug", romSlug.ifBlank { "unknown" })
            putString("mode", mode.ifBlank { "solo" })
        }
    }

    fun logGameSessionEnd(
        romSlug: String,
        durationSec: Long,
        mode: String,
    ) {
        logEvent("game_session_end") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
            putLong("duration_sec", durationSec.coerceAtLeast(0L))
            putString("mode", mode.ifBlank { "solo" })
        }
    }

    fun logNetplayRoomCreated(romSlug: String) {
        logEvent("netplay_room_created") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
        }
    }

    fun logNetplayRoomJoined(romSlug: String) {
        logEvent("netplay_room_joined") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
        }
    }

    fun logLeaderboardSubmit(
        romSlug: String,
        score: Long,
        creditsUsed: Int,
    ) {
        logEvent("leaderboard_score_submit") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
            putLong(FirebaseAnalytics.Param.SCORE, score)
            putLong("credits_used", creditsUsed.toLong())
        }
    }

    fun logGameDetailViewed(
        gameId: String,
        romSlug: String,
    ) {
        logEvent("game_detail_viewed") {
            putString("game_id", gameId.ifBlank { "unknown" })
            putString("rom_slug", romSlug.ifBlank { "unknown" })
        }
    }

    fun logGameGalleryOpened(
        romSlug: String,
        imageType: String,
    ) {
        logEvent("game_gallery_opened") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
            putString("image_type", imageType.ifBlank { "unknown" })
        }
    }

    fun logGameTrailerOpened(romSlug: String) {
        logEvent("game_trailer_opened") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
        }
    }

    fun logMatchmakingStarted(romSlug: String) {
        logEvent("matchmaking_started") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
        }
    }

    fun logMatchmakingMatched(
        romSlug: String,
        source: String,
        waitTimeSec: Long,
    ) {
        logEvent("matchmaking_matched") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
            putString("source", source.ifBlank { "queue" })
            putLong("wait_time_sec", waitTimeSec.coerceAtLeast(0L))
        }
    }

    fun logMatchmakingCancelled(
        romSlug: String,
        waitTimeSec: Long,
        reason: String,
    ) {
        logEvent("matchmaking_cancelled") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
            putLong("wait_time_sec", waitTimeSec.coerceAtLeast(0L))
            putString("reason", reason.ifBlank { "user" })
        }
    }

    fun logMatchmakingFailed(
        romSlug: String,
        errorType: String,
    ) {
        logEvent("matchmaking_failed") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
            putString("error_type", errorType.ifBlank { "unknown" })
        }
    }

    fun logRoomPlayerBanned(romSlug: String) {
        logEvent("room_player_banned") {
            putString("rom_slug", romSlug.ifBlank { "unknown" })
        }
    }

    fun logRoomDeepLinkOpened(
        roomIdPresent: Boolean,
        outcome: String,
    ) {
        logEvent("room_deeplink_opened") {
            putString("room_id_present", roomIdPresent.toString())
            putString("outcome", outcome.ifBlank { "unknown" })
        }
    }

    private inline fun logEvent(
        name: String,
        block: Bundle.() -> Unit,
    ) {
        runCatching {
            val bundle = Bundle().apply(block)
            analytics?.logEvent(name, bundle)
        }
    }
}
