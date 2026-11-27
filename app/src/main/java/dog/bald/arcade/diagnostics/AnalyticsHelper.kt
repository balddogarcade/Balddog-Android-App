package dog.bald.arcade.diagnostics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

internal fun interface AnalyticsSink {
    fun logEvent(
        name: String,
        params: Map<String, Any>,
    )
}

object AnalyticsHelper {
    private const val MAX_PARAM_LENGTH = 100

    @Volatile
    private var analytics: FirebaseAnalytics? = null

    @Volatile
    internal var testSink: AnalyticsSink? = null

    fun init(context: Context) {
        if (analytics == null) {
            analytics = runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }.getOrNull()
        }
    }

    internal fun sanitizeParam(
        value: String?,
        fallback: String = "unknown",
    ): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallback
        return if (trimmed.length > MAX_PARAM_LENGTH) {
            trimmed.substring(0, MAX_PARAM_LENGTH)
        } else {
            trimmed
        }
    }

    fun setUserId(userId: String?) {
        runCatching {
            analytics?.setUserId(userId?.takeIf { it.isNotBlank() })
        }
    }

    fun logScreenView(
        screenName: String,
        screenClass: String? = null,
    ) {
        logEventMap(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            buildMap {
                put(FirebaseAnalytics.Param.SCREEN_NAME, sanitizeParam(screenName))
                if (!screenClass.isNullOrBlank()) {
                    put(FirebaseAnalytics.Param.SCREEN_CLASS, sanitizeParam(screenClass))
                }
            },
        )
    }

    fun logAuthEvent(
        method: String,
        isSignUp: Boolean,
    ) {
        val eventName = if (isSignUp) FirebaseAnalytics.Event.SIGN_UP else FirebaseAnalytics.Event.LOGIN
        logEventMap(
            eventName,
            mapOf(FirebaseAnalytics.Param.METHOD to sanitizeParam(method)),
        )
    }

    fun logPasswordResetRequested() {
        logEventMap("password_reset_requested", emptyMap())
    }

    fun logGameLaunch(
        gameId: String,
        romSlug: String,
        mode: String,
    ) {
        logEventMap(
            "game_launch",
            mapOf(
                "game_id" to sanitizeParam(gameId),
                "rom_slug" to sanitizeParam(romSlug),
                "mode" to sanitizeParam(mode, "solo"),
            ),
        )
    }

    fun logGameSessionEnd(
        romSlug: String,
        durationSec: Long,
        mode: String,
    ) {
        logEventMap(
            "game_session_end",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "duration_sec" to durationSec.coerceAtLeast(0L),
                "mode" to sanitizeParam(mode, "solo"),
            ),
        )
    }

    fun logNetplayRoomCreated(
        romSlug: String,
        mode: String = "unknown",
        isPrivate: Boolean = false,
    ) {
        logEventMap(
            "netplay_room_created",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "mode" to sanitizeParam(mode),
                "is_private" to isPrivate.toString(),
            ),
        )
    }

    fun logNetplayRoomJoined(
        romSlug: String,
        mode: String = "unknown",
        source: String = "list",
    ) {
        logEventMap(
            "netplay_room_joined",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "mode" to sanitizeParam(mode),
                "source" to sanitizeParam(source, "list"),
            ),
        )
    }

    fun logRoomLookup(found: Boolean) {
        logEventMap(
            "room_lookup",
            mapOf("found" to found.toString()),
        )
    }

    fun logLeaderboardSubmit(
        romSlug: String,
        score: Long,
        creditsUsed: Int,
        outcome: String = "accepted",
    ) {
        logEventMap(
            "leaderboard_score_submit",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                FirebaseAnalytics.Param.SCORE to score.coerceAtLeast(0L),
                "credits_used" to creditsUsed.coerceAtLeast(0).toLong(),
                "outcome" to sanitizeParam(outcome, "accepted"),
            ),
        )
    }

    fun logNewRecordAchieved(
        romSlug: String,
        score: Long,
        rank: Int?,
    ) {
        logEventMap(
            "new_record_achieved",
            buildMap {
                put("rom_slug", sanitizeParam(romSlug))
                put(FirebaseAnalytics.Param.SCORE, score.coerceAtLeast(0L))
                if (rank != null) {
                    put("rank", rank.toLong())
                }
            },
        )
    }

    fun logGameDetailViewed(
        gameId: String,
        romSlug: String,
    ) {
        logEventMap(
            "game_detail_viewed",
            mapOf(
                "game_id" to sanitizeParam(gameId),
                "rom_slug" to sanitizeParam(romSlug),
            ),
        )
    }

    fun logGameGalleryOpened(
        romSlug: String,
        imageType: String,
    ) {
        logEventMap(
            "game_gallery_opened",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "image_type" to sanitizeParam(imageType),
            ),
        )
    }

    fun logGameTrailerOpened(romSlug: String) {
        logEventMap(
            "game_trailer_opened",
            mapOf("rom_slug" to sanitizeParam(romSlug)),
        )
    }

    fun logCatalogSearch(
        query: String,
        resultsCount: Int,
    ) {
        logEventMap(
            "catalog_search",
            mapOf(
                "search_term" to sanitizeParam(query, ""),
                "results_count" to resultsCount.coerceAtLeast(0).toLong(),
            ),
        )
    }

    fun logFilterChange(
        filterType: String,
        value: String,
        source: String,
    ) {
        logEventMap(
            "filter_change",
            mapOf(
                "filter_type" to sanitizeParam(filterType),
                "value" to sanitizeParam(value),
                "source" to sanitizeParam(source),
            ),
        )
    }

    fun logRomDownloadStarted(romSlug: String) {
        logEventMap(
            "rom_download_started",
            mapOf("rom_slug" to sanitizeParam(romSlug)),
        )
    }

    fun logRomDownloadCompleted(
        romSlug: String,
        fromCache: Boolean,
    ) {
        logEventMap(
            "rom_download_completed",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "from_cache" to fromCache.toString(),
            ),
        )
    }

    fun logRomDownloadFailed(
        romSlug: String,
        errorType: String,
    ) {
        logEventMap(
            "rom_download_failed",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "error_type" to sanitizeParam(errorType),
            ),
        )
    }

    fun logRomDeleted(romSlug: String) {
        logEventMap(
            "rom_deleted",
            mapOf("rom_slug" to sanitizeParam(romSlug)),
        )
    }

    fun logShare(
        contentType: String,
        itemId: String,
        method: String = "android_sharesheet",
    ) {
        logEventMap(
            FirebaseAnalytics.Event.SHARE,
            mapOf(
                FirebaseAnalytics.Param.CONTENT_TYPE to sanitizeParam(contentType),
                FirebaseAnalytics.Param.ITEM_ID to sanitizeParam(itemId),
                FirebaseAnalytics.Param.METHOD to sanitizeParam(method, "android_sharesheet"),
            ),
        )
    }

    fun logReplayControl(
        action: String,
        speed: Float? = null,
    ) {
        logEventMap(
            "replay_control",
            buildMap {
                put("action", sanitizeParam(action))
                if (speed != null) {
                    put("speed", speed.toString())
                }
            },
        )
    }

    fun logMediaExport(
        type: String,
        romSlug: String,
        success: Boolean,
    ) {
        logEventMap(
            "media_export",
            mapOf(
                "type" to sanitizeParam(type),
                "rom_slug" to sanitizeParam(romSlug),
                "success" to success.toString(),
            ),
        )
    }

    fun logInGameMenuAction(
        action: String,
        romSlug: String,
    ) {
        logEventMap(
            "ingame_menu_action",
            mapOf(
                "action" to sanitizeParam(action),
                "rom_slug" to sanitizeParam(romSlug),
            ),
        )
    }

    fun logFollowToggled(
        isFollowing: Boolean,
        source: String,
    ) {
        logEventMap(
            "follow_toggled",
            mapOf(
                "following" to isFollowing.toString(),
                "source" to sanitizeParam(source),
            ),
        )
    }

    fun logProfileUpdated() {
        logEventMap("profile_updated", emptyMap())
    }

    fun logAccountDeleted() {
        logEventMap("account_deleted", emptyMap())
    }

    fun logAppUpdateEvent(
        stage: String,
        versionTag: String = "",
    ) {
        logEventMap(
            "app_update",
            buildMap {
                put("stage", sanitizeParam(stage))
                if (versionTag.isNotBlank()) {
                    put("version_tag", sanitizeParam(versionTag))
                }
            },
        )
    }

    fun logWikiInteraction(
        sectionId: String,
        action: String,
    ) {
        logEventMap(
            "wiki_interaction",
            mapOf(
                "section_id" to sanitizeParam(sectionId),
                "action" to sanitizeParam(action),
            ),
        )
    }

    fun logMatchmakingStarted(romSlug: String) {
        logEventMap(
            "matchmaking_started",
            mapOf("rom_slug" to sanitizeParam(romSlug)),
        )
    }

    fun logMatchmakingMatched(
        romSlug: String,
        source: String,
        waitTimeSec: Long,
    ) {
        logEventMap(
            "matchmaking_matched",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "source" to sanitizeParam(source, "queue"),
                "wait_time_sec" to waitTimeSec.coerceAtLeast(0L),
            ),
        )
    }

    fun logMatchmakingCancelled(
        romSlug: String,
        waitTimeSec: Long,
        reason: String,
    ) {
        logEventMap(
            "matchmaking_cancelled",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "wait_time_sec" to waitTimeSec.coerceAtLeast(0L),
                "reason" to sanitizeParam(reason, "user"),
            ),
        )
    }

    fun logMatchmakingFailed(
        romSlug: String,
        errorType: String,
    ) {
        logEventMap(
            "matchmaking_failed",
            mapOf(
                "rom_slug" to sanitizeParam(romSlug),
                "error_type" to sanitizeParam(errorType),
            ),
        )
    }

    fun logRoomPlayerBanned(romSlug: String) {
        logEventMap(
            "room_player_banned",
            mapOf("rom_slug" to sanitizeParam(romSlug)),
        )
    }

    fun logRoomDeepLinkOpened(
        roomIdPresent: Boolean,
        outcome: String,
    ) {
        logEventMap(
            "room_deeplink_opened",
            mapOf(
                "room_id_present" to roomIdPresent.toString(),
                "outcome" to sanitizeParam(outcome),
            ),
        )
    }

    private fun logEventMap(
        name: String,
        params: Map<String, Any>,
    ) {
        testSink?.let { sink ->
            sink.logEvent(name, params)
            return
        }
        runCatching {
            val bundle =
                Bundle().apply {
                    params.forEach { (key, value) ->
                        when (value) {
                            is Long -> putLong(key, value)
                            is Int -> putLong(key, value.toLong())
                            is Double -> putDouble(key, value)
                            is Float -> putDouble(key, value.toDouble())
                            is Boolean -> putString(key, value.toString())
                            else -> putString(key, value.toString())
                        }
                    }
                }
            analytics?.logEvent(name, bundle)
        }
    }
}
