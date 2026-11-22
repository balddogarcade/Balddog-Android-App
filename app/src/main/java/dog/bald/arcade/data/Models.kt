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
    @SerialName("size_bytes") val sizeBytes: Long = 0L
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
    @SerialName("total_play_seconds") val totalPlaySeconds: Long = 0L
)

@Serializable
data class ProfileDto(
    val id: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("total_plays") val totalPlays: Int = 0,
    @SerialName("total_play_seconds") val totalPlaySeconds: Long = 0L,
    @SerialName("is_anonymous") val isAnonymous: Boolean = false
)

@Serializable
data class UserGameStatDto(
    @SerialName("user_id") val userId: String,
    @SerialName("game_id") val gameId: String,
    @SerialName("play_seconds") val playSeconds: Long = 0L,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("last_played_at") val lastPlayedAt: String = ""
)

@Serializable
data class RoomSlotInfo(
    @SerialName("user_id") val userId: String,
    val username: String,
    val endpoint: String,
    @SerialName("voice_endpoint") val voiceEndpoint: String? = null,
    val ready: Boolean = false
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
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class RoomMessageDto(
    val id: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    val message: String,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class HighScoreDto(
    val id: String,
    @SerialName("game_id") val gameId: String,
    @SerialName("user_id") val userId: String,
    val score: Long,
    @SerialName("achieved_at") val achievedAt: String = ""
)
