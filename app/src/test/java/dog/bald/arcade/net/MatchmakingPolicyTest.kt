package dog.bald.arcade.net

import dog.bald.arcade.data.RoomDto
import dog.bald.arcade.data.RoomSlotInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchmakingPolicyTest {
    private val validUuid = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `parseRoomIdFromUri parses valid https and custom scheme links`() {
        assertEquals(validUuid, MatchmakingPolicy.parseRoomIdFromUri("https://bald.dog/room/$validUuid"))
        assertEquals(validUuid, MatchmakingPolicy.parseRoomIdFromUri("https://www.bald.dog/room/$validUuid"))
        assertEquals(validUuid, MatchmakingPolicy.parseRoomIdFromUri("balddog://room/$validUuid"))
        assertEquals(validUuid, MatchmakingPolicy.parseRoomIdFromUri("dog.bald.arcade://room/$validUuid"))
    }

    @Test
    fun `parseRoomIdFromUri rejects invalid hosts paths or non-uuid ids`() {
        assertNull(MatchmakingPolicy.parseRoomIdFromUri(null))
        assertNull(MatchmakingPolicy.parseRoomIdFromUri(""))
        assertNull(MatchmakingPolicy.parseRoomIdFromUri("https://evil.com/room/$validUuid"))
        assertNull(MatchmakingPolicy.parseRoomIdFromUri("https://bald.dog/games/$validUuid"))
        assertNull(MatchmakingPolicy.parseRoomIdFromUri("https://bald.dog/room/not-a-uuid"))
    }

    @Test
    fun `evaluateRoomJoinEligibility handles not found, playing, banned, full, and eligible`() {
        assertTrue(MatchmakingPolicy.evaluateRoomJoinEligibility(null, "u1") is RoomJoinEligibility.Ineligible)

        val baseRoom =
            RoomDto(
                id = validUuid,
                code = "1234A",
                hostId = "host-1",
                gameId = "g1",
                mode = "online",
                status = "waiting",
                maxPlayers = 2,
                slotsJson =
                    mapOf(
                        "0" to RoomSlotInfo(userId = "host-1", username = "Host", endpoint = "1.2.3.4:7000", ready = true),
                    ),
            )

        assertEquals(
            RoomJoinEligibility.Eligible,
            MatchmakingPolicy.evaluateRoomJoinEligibility(baseRoom, "u2"),
        )

        val bannedRoom = baseRoom.copy(bannedUserIds = listOf("u2"))
        val bannedRes = MatchmakingPolicy.evaluateRoomJoinEligibility(bannedRoom, "u2") as RoomJoinEligibility.Ineligible
        assertEquals("banned", bannedRes.reasonCode)

        val fullRoom =
            baseRoom.copy(
                slotsJson =
                    baseRoom.slotsJson + ("1" to RoomSlotInfo(userId = "u3", username = "P2", endpoint = "1.2.3.5:7000")),
            )
        val fullRes = MatchmakingPolicy.evaluateRoomJoinEligibility(fullRoom, "u2") as RoomJoinEligibility.Ineligible
        assertEquals("full", fullRes.reasonCode)

        val playingRoom = baseRoom.copy(status = "playing")
        val playingRes = MatchmakingPolicy.evaluateRoomJoinEligibility(playingRoom, "u2") as RoomJoinEligibility.Ineligible
        assertEquals("not_waiting_playing", playingRes.reasonCode)
    }

    @Test
    fun `formatElapsedMmSs formats seconds accurately`() {
        assertEquals("00:00", MatchmakingPolicy.formatElapsedMmSs(-5))
        assertEquals("00:09", MatchmakingPolicy.formatElapsedMmSs(9))
        assertEquals("01:05", MatchmakingPolicy.formatElapsedMmSs(65))
        assertEquals("10:00", MatchmakingPolicy.formatElapsedMmSs(600))
    }

    @Test
    fun `MatchmakingTransitionGate allows only one transition per search cycle`() {
        val gate = MatchmakingTransitionGate()
        assertFalse(gate.tryConsume(isCurrentlySearching = false))
        assertTrue(gate.tryConsume(isCurrentlySearching = true))
        assertFalse(gate.tryConsume(isCurrentlySearching = true))
        gate.reset()
        assertTrue(gate.tryConsume(isCurrentlySearching = true))
    }
}
