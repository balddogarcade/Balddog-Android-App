package dog.bald.arcade.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSlotAvatarTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun roomSlotInfoDecodesWithAvatarSeed() {
        val rawJson = """
            {
                "user_id": "usr-1234",
                "username": "PlayerOne",
                "endpoint": "192.168.1.50:7000",
                "voice_endpoint": "192.168.1.50:8000",
                "ready": true,
                "avatar_seed": "av_custom_seed_abc"
            }
        """.trimIndent()

        val slot = json.decodeFromString<RoomSlotInfo>(rawJson)
        assertEquals("usr-1234", slot.userId)
        assertEquals("PlayerOne", slot.username)
        assertEquals("192.168.1.50:7000", slot.endpoint)
        assertEquals("192.168.1.50:8000", slot.voiceEndpoint)
        assertTrue(slot.ready)
        assertEquals("av_custom_seed_abc", slot.avatarSeed)
    }

    @Test
    fun roomSlotInfoDecodesLegacyJsonWithoutAvatarSeed() {
        val legacyJson = """
            {
                "user_id": "usr-5678",
                "username": "OldPlayer",
                "endpoint": "192.168.1.60:7000",
                "ready": false
            }
        """.trimIndent()

        val slot = json.decodeFromString<RoomSlotInfo>(legacyJson)
        assertEquals("usr-5678", slot.userId)
        assertEquals("OldPlayer", slot.username)
        assertNull(slot.avatarSeed)
    }

    @Test
    fun roomSlotInfoSerializesAvatarSeedCorrectly() {
        val slot = RoomSlotInfo(
            userId = "usr-host",
            username = "HostUser",
            endpoint = "127.0.0.1:7000",
            ready = true,
            avatarSeed = "av_host_seed_99"
        )

        val encoded = json.encodeToString(slot)
        assertTrue(encoded.contains("\"avatar_seed\":\"av_host_seed_99\""))

        val decoded = json.decodeFromString<RoomSlotInfo>(encoded)
        assertEquals("av_host_seed_99", decoded.avatarSeed)
    }

    @Test
    fun effectiveAvatarSeedSelectionForLocalAndRemotePlayers() {
        fun resolveEffectiveSeed(
            isMine: Boolean,
            myAvatarSeed: String?,
            occupant: RoomSlotInfo?
        ): String {
            return when {
                isMine && !myAvatarSeed.isNullOrBlank() -> myAvatarSeed
                occupant != null && !occupant.avatarSeed.isNullOrBlank() -> occupant.avatarSeed
                else -> occupant?.username?.ifBlank { occupant.userId }.orEmpty()
            }
        }

        // 1. Local player with configured profile seed -> always uses local profile seed
        val localSlot = RoomSlotInfo(
            userId = "my-uid",
            username = "MyName",
            endpoint = "endpoint",
            avatarSeed = null // even if slot in DB has no seed yet
        )
        val localSeed = resolveEffectiveSeed(
            isMine = true,
            myAvatarSeed = "av_my_personal_seed",
            occupant = localSlot
        )
        assertEquals("av_my_personal_seed", localSeed)

        // 2. Remote player with avatar_seed in slot -> uses their slot avatar_seed
        val remoteSlotWithSeed = RoomSlotInfo(
            userId = "peer-uid",
            username = "PeerUser",
            endpoint = "endpoint",
            avatarSeed = "av_peer_seed_123"
        )
        val remoteSeed = resolveEffectiveSeed(
            isMine = false,
            myAvatarSeed = "av_my_personal_seed",
            occupant = remoteSlotWithSeed
        )
        assertEquals("av_peer_seed_123", remoteSeed)

        // 3. Remote player without avatar_seed (legacy) -> falls back to username
        val remoteSlotWithoutSeed = RoomSlotInfo(
            userId = "peer-legacy-uid",
            username = "LegacyPeer",
            endpoint = "endpoint",
            avatarSeed = null
        )
        val legacySeed = resolveEffectiveSeed(
            isMine = false,
            myAvatarSeed = "av_my_personal_seed",
            occupant = remoteSlotWithoutSeed
        )
        assertEquals("LegacyPeer", legacySeed)
    }
}
