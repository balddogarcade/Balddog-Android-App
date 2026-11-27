package dog.bald.arcade.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchmakingModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `RoomDto deserializes banned_user_ids and defaults cleanly`() {
        val raw =
            """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "code": "9012Z",
              "host_id": "u1",
              "game_id": "g1",
              "mode": "online",
              "status": "waiting",
              "max_players": 4,
              "banned_user_ids": ["u9", "u10"]
            }
            """.trimIndent()
        val room = json.decodeFromString<RoomDto>(raw)
        assertEquals(listOf("u9", "u10"), room.bannedUserIds)
        assertEquals(4, room.maxPlayers)
    }

    @Test
    fun `MatchmakingResultDto and MatchmakingStatsDto deserialize correctly`() {
        val resRaw = """{"status":"matched","room_id":"r1","source":"backfill"}"""
        val res = json.decodeFromString<MatchmakingResultDto>(resRaw)
        assertEquals("matched", res.status)
        assertEquals("r1", res.roomId)
        assertEquals("backfill", res.source)

        val statsRaw =
            """
            {
              "total_searching": 3,
              "total_playing": 8,
              "by_game": [
                {"game_slug": "sf2ce", "searching": 2, "playing": 4, "open_rooms": 1}
              ]
            }
            """.trimIndent()
        val stats = json.decodeFromString<MatchmakingStatsDto>(statsRaw)
        assertEquals(3, stats.totalSearching)
        assertEquals(8, stats.totalPlaying)
        assertEquals(1, stats.byGame.size)
        assertEquals("sf2ce", stats.byGame.first().gameSlug)
        assertTrue(stats.byGame.first().openRooms == 1)
    }
}
