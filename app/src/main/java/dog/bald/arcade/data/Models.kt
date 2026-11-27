package dog.bald.arcade.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GameBiosDto(
    val id: String,
    val system: String,
    val filename: String,
    @SerialName("ipfs_cid") val ipfsCid: String,
    val sha256: String,
    val crc32: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
)

@Serializable
data class GameDto(
    val id: String,
    val slug: String,
    val title: String,
    val system: String,
    @SerialName("rom_filename") val romFilename: String,
    @SerialName("ipfs_cid") val ipfsCid: String,
    val sha256: String,
    val crc32: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("max_players") val maxPlayers: Int = 2,
    @SerialName("is_coop") val isCoop: Boolean = true,
    @SerialName("parent_game_id") val parentGameId: String? = null,
    @SerialName("bios_id") val biosId: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("download_count") val downloadCount: Long = 0L,
    @SerialName("total_play_seconds") val totalPlaySeconds: Long = 0L,
    @SerialName("max_valid_score") val maxValidScore: Long? = null,
    @SerialName("score_config") val scoreConfig: ScoreConfigDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("game_name") val gameName: String? = null,
    @SerialName("adb_title") val adbTitle: String? = null,
    @SerialName("short_title") val shortTitle: String? = null,
    val manufacturer: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val serie: String? = null,
    val players: Int? = null,
    val nplayers: String? = null,
    @SerialName("emulator_name") val emulatorName: String? = null,
    @SerialName("input_controls") val inputControls: String? = null,
    @SerialName("input_buttons") val inputButtons: Int? = null,
    @SerialName("buttons_colors") val buttonsColors: String? = null,
    val history: String? = null,
    @SerialName("history_copyright_long") val historyCopyrightLong: String? = null,
    @SerialName("youtube_video_id") val youtubeVideoId: String? = null,
    @SerialName("url_image_ingame") val urlImageIngame: String? = null,
    @SerialName("url_image_title") val urlImageTitle: String? = null,
    @SerialName("url_image_marquee") val urlImageMarquee: String? = null,
    @SerialName("url_image_cabinet") val urlImageCabinet: String? = null,
    @SerialName("url_image_flyer") val urlImageFlyer: String? = null,
    @SerialName("url_icon") val urlIcon: String? = null,
)

@Serializable
data class ScoreConfigDto(
    val lboard: String = "",
    val format: String = "rcheevos",
)

@Serializable
data class SocialLinksDto(
    val x: String? = null,
    val instagram: String? = null,
    val youtube: String? = null,
    val twitch: String? = null,
    val discord: String? = null,
    val github: String? = null,
    val website: String? = null,
)

@Serializable
data class ProfileDto(
    val id: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_seed") val avatarSeed: String? = null,
    val bio: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("social_links") val socialLinks: SocialLinksDto = SocialLinksDto(),
    @SerialName("total_plays") val totalPlays: Int = 0,
    @SerialName("total_play_seconds") val totalPlaySeconds: Long = 0L,
    @SerialName("is_anonymous") val isAnonymous: Boolean = false,
    @SerialName("welcome_email_sent") val welcomeEmailSent: Boolean = false,
)

@Serializable
data class UserGameStatDto(
    @SerialName("user_id") val userId: String,
    @SerialName("game_id") val gameId: String,
    @SerialName("play_seconds") val playSeconds: Long = 0L,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("last_played_at") val lastPlayedAt: String = "",
)

@Serializable
data class RoomSlotInfo(
    @SerialName("user_id") val userId: String,
    val username: String,
    val endpoint: String,
    @SerialName("voice_endpoint") val voiceEndpoint: String? = null,
    val ready: Boolean = false,
    @SerialName("avatar_seed") val avatarSeed: String? = null,
)

@Serializable
data class RoomDto(
    val id: String,
    val code: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("game_id") val gameId: String,
    val mode: String,
    val status: String,
    @SerialName("max_players") val maxPlayers: Int = 2,
    @SerialName("slots_json") val slotsJson: Map<String, RoomSlotInfo> = emptyMap(),
    @SerialName("host_endpoint") val hostEndpoint: String? = null,
    @SerialName("local_ip") val localIp: String? = null,
    @SerialName("local_port") val localPort: Int? = null,
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("banned_user_ids") val bannedUserIds: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class MatchmakingResultDto(
    val status: String,
    @SerialName("room_id") val roomId: String? = null,
    val source: String = "queue",
)

@Serializable
data class MatchmakingQueueRowDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("game_id") val gameId: String,
    @SerialName("game_slug") val gameSlug: String,
    val status: String,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class GameMatchmakingStatDto(
    @SerialName("game_slug") val gameSlug: String,
    val searching: Int = 0,
    val playing: Int = 0,
    @SerialName("open_rooms") val openRooms: Int = 0,
)

@Serializable
data class MatchmakingStatsDto(
    @SerialName("total_searching") val totalSearching: Int = 0,
    @SerialName("total_playing") val totalPlaying: Int = 0,
    @SerialName("by_game") val byGame: List<GameMatchmakingStatDto> = emptyList(),
)

@Serializable
data class RoomMessageDto(
    val id: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    val message: String,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class HighScoreDto(
    val id: String,
    @SerialName("game_id") val gameId: String,
    @SerialName("user_id") val userId: String,
    val score: Long,
    @SerialName("achieved_at") val achievedAt: String = "",
)

@Serializable
data class LeaderboardEntryDto(
    @SerialName("id") val id: String? = null,
    val rank: Long,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_seed") val avatarSeed: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean = false,
    val score: Long,
    @SerialName("achieved_at") val achievedAt: String = "",
    @SerialName("replay_path") val replayPath: String? = null,
    @SerialName("verification_status") val verificationStatus: String = "unverified",
)

@Serializable
data class SubmitScoreResultDto(
    val accepted: Boolean = false,
    @SerialName("is_new_record") val isNewRecord: Boolean = false,
    @SerialName("best_score") val bestScore: Long = 0L,
    val rank: Long? = null,
    val reason: String? = null,
)

data class GameLeaderboardData(
    val entries: List<LeaderboardEntryDto> = emptyList(),
    val myEntry: LeaderboardEntryDto? = null,
)

@Serializable
data class MyHighScoreDto(
    @SerialName("id") val id: String? = null,
    @SerialName("game_id") val gameId: String,
    val score: Long,
    @SerialName("achieved_at") val achievedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val rank: Long,
    @SerialName("total_players") val totalPlayers: Long = 0L,
    @SerialName("replay_path") val replayPath: String? = null,
    @SerialName("replay_size_bytes") val replaySizeBytes: Int? = null,
    @SerialName("verification_status") val verificationStatus: String = "unverified",
)

@Serializable
data class MatchHistoryPlayerDto(
    @SerialName("user_id") val userId: String,
    val username: String,
    val slot: Int = 0,
)

@Serializable
data class MatchHistoryDto(
    val id: String,
    @SerialName("game_id") val gameId: String,
    val mode: String,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("players_json") val playersJson: List<MatchHistoryPlayerDto> = emptyList(),
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val score: Long? = null,
)

@Serializable
data class GlobalLeaderboardEntryDto(
    @SerialName("global_rank") val globalRank: Long,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("avatar_seed") val avatarSeed: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("global_points") val globalPoints: Long = 0L,
    @SerialName("first_places") val firstPlaces: Long = 0L,
    val podiums: Long = 0L,
    @SerialName("total_1cc") val total1cc: Long = 0L,
    @SerialName("total_play_seconds") val totalPlaySeconds: Long = 0L,
    @SerialName("is_following_by_me") val isFollowingByMe: Boolean = false,
)

@Serializable
data class GameLeaderboardV2EntryDto(
    @SerialName("id") val id: String? = null,
    val rank: Long,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("avatar_seed") val avatarSeed: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val score: Long = 0L,
    @SerialName("play_seconds") val playSeconds: Long = 0L,
    @SerialName("achieved_at") val achievedAt: String = "",
    @SerialName("replay_path") val replayPath: String? = null,
    @SerialName("verification_status") val verificationStatus: String = "unverified",
    @SerialName("is_following_by_me") val isFollowingByMe: Boolean = false,
)

@Serializable
data class GameTopPlayerDto(
    val rank: Long,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("country_code") val countryCode: String? = null,
    val score: Long = 0L,
)

@Serializable
data class GameRankingSummaryDto(
    @SerialName("game_id") val gameId: String,
    @SerialName("total_players") val totalPlayers: Long = 0L,
    @SerialName("my_rank") val myRank: Long? = null,
    @SerialName("my_score") val myScore: Long? = null,
    @SerialName("top3_json") val top3: List<GameTopPlayerDto> = emptyList(),
)

@Serializable
data class PublicProfileHighScoreDto(
    @SerialName("game_id") val gameId: String,
    val score: Long = 0L,
    @SerialName("achieved_at") val achievedAt: String = "",
    val rank: Long = 0L,
    @SerialName("total_players") val totalPlayers: Long = 0L,
    @SerialName("replay_path") val replayPath: String? = null,
    @SerialName("verification_status") val verificationStatus: String = "unverified",
)

@Serializable
data class PublicProfileMatchDto(
    val id: String,
    @SerialName("game_id") val gameId: String,
    val mode: String,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val score: Long? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class PublicProfileDto(
    val id: String,
    val username: String,
    @SerialName("avatar_seed") val avatarSeed: String? = null,
    val bio: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("social_links") val socialLinks: SocialLinksDto = SocialLinksDto(),
    @SerialName("total_plays") val totalPlays: Int? = null,
    @SerialName("total_play_seconds") val totalPlaySeconds: Long? = null,
    @SerialName("global_points") val globalPoints: Long = 0L,
    @SerialName("global_rank") val globalRank: Long? = null,
    @SerialName("first_places") val firstPlaces: Long = 0L,
    val podiums: Long = 0L,
    @SerialName("total_1cc") val total1cc: Long = 0L,
    @SerialName("verified_replays") val verifiedReplays: Long = 0L,
    @SerialName("netplay_matches") val netplayMatches: Long = 0L,
    @SerialName("followers_count") val followersCount: Long = 0L,
    @SerialName("following_count") val followingCount: Long = 0L,
    @SerialName("is_following_by_me") val isFollowingByMe: Boolean = false,
    @SerialName("high_scores") val highScores: List<PublicProfileHighScoreDto> = emptyList(),
    @SerialName("recent_matches") val recentMatches: List<PublicProfileMatchDto> = emptyList(),
)

@Serializable
data class FollowUserDto(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("avatar_seed") val avatarSeed: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("global_points") val globalPoints: Long = 0L,
    @SerialName("is_following_by_me") val isFollowingByMe: Boolean = false,
)

enum class ArcadeRank(
    val title: String,
    val minPoints: Long,
) {
    NOVATO("NOVATO", 0L),
    RETADOR("RETADOR", 150L),
    LUCHADOR("LUCHADOR", 450L),
    MAESTRO("MAESTRO", 900L),
    GRAN_MAESTRO("GRAN MAESTRO", 1500L),
    LEYENDA("LEYENDA", 2000L),
    ;

    companion object {
        fun fromPoints(points: Long): ArcadeRank = entries.lastOrNull { points >= it.minPoints } ?: NOVATO
    }
}

data class ArcadeBadge(
    val id: String,
    val title: String,
    val subtitle: String,
    val tier: String? = null,
)

fun computeArcadeBadges(
    firstPlaces: Long,
    podiums: Long,
    total1cc: Long,
    verifiedReplays: Long,
    totalPlaySeconds: Long?,
    netplayMatches: Long,
): List<ArcadeBadge> {
    val badges = mutableListOf<ArcadeBadge>()
    if (firstPlaces > 0) {
        badges +=
            ArcadeBadge(
                id = "king",
                title = "REY DEL ARCADE",
                subtitle = "$firstPlaces × #1",
            )
    }
    if (podiums > 0) {
        badges +=
            ArcadeBadge(
                id = "podium",
                title = "PODIO",
                subtitle = "$podiums × Top 3",
            )
    }
    if (total1cc > 0) {
        badges +=
            ArcadeBadge(
                id = "1cc_master",
                title = "1CC MASTER",
                subtitle = "$total1cc juegos",
            )
    }
    if (verifiedReplays > 0) {
        badges +=
            ArcadeBadge(
                id = "verified_replay",
                title = "REPLAY VERIFICADO",
                subtitle = "$verifiedReplays auditados",
            )
    }
    val hours = (totalPlaySeconds ?: 0L) / 3600L
    when {
        hours >= 250L -> badges += ArcadeBadge("veteran", "VETERANO", "${hours}h", "ORO")
        hours >= 100L -> badges += ArcadeBadge("veteran", "VETERANO", "${hours}h", "PLATA")
        hours >= 25L -> badges += ArcadeBadge("veteran", "VETERANO", "${hours}h", "BRONCE")
    }
    when {
        netplayMatches >= 250L -> badges += ArcadeBadge("netplay", "ANFITRIÓN NETPLAY", "$netplayMatches salas", "ORO")
        netplayMatches >= 100L -> badges += ArcadeBadge("netplay", "ANFITRIÓN NETPLAY", "$netplayMatches salas", "PLATA")
        netplayMatches >= 25L -> badges += ArcadeBadge("netplay", "ANFITRIÓN NETPLAY", "$netplayMatches salas", "BRONCE")
    }
    return badges
}
