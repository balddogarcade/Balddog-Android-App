package dog.bald.arcade.ui.components

import dog.bald.arcade.data.MyHighScoreDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NewRecordDetectionTest {

    private fun scoreEntry(
        gameId: String,
        score: Long,
        rank: Long
    ) = MyHighScoreDto(
        gameId = gameId,
        score = score,
        rank = rank
    )

    @Test
    fun first1ccRecordOutsideTopTenIsDetected() {
        val prev = emptyMap<String, MyHighScoreDto>()
        val updated = listOf(scoreEntry(gameId = "sf2", score = 15000L, rank = 27L))

        val detected = detectImprovedHighScore(prev, updated)
        assertNotNull(detected)
        assertEquals("sf2", detected!!.gameId)
        assertEquals(27L, detected.rank)
        assertEquals(15000L, detected.score)
    }

    @Test
    fun first1ccRecordInsideTopTenIsDetected() {
        val prev = emptyMap<String, MyHighScoreDto>()
        val updated = listOf(scoreEntry(gameId = "mslug", score = 99000L, rank = 3L))

        val detected = detectImprovedHighScore(prev, updated)
        assertNotNull(detected)
        assertEquals(3L, detected!!.rank)
    }

    @Test
    fun higherScoreAtSameRankIsDetected() {
        val prev = mapOf("kof98" to scoreEntry(gameId = "kof98", score = 50000L, rank = 1L))
        val updated = listOf(scoreEntry(gameId = "kof98", score = 62000L, rank = 1L))

        val detected = detectImprovedHighScore(prev, updated)
        assertNotNull(detected)
        assertEquals(62000L, detected!!.score)
    }

    @Test
    fun improvedRankWithSameScoreIsDetected() {
        val prev = mapOf("dkong" to scoreEntry(gameId = "dkong", score = 40000L, rank = 5L))
        val updated = listOf(scoreEntry(gameId = "dkong", score = 40000L, rank = 4L))

        val detected = detectImprovedHighScore(prev, updated)
        assertNotNull(detected)
        assertEquals(4L, detected!!.rank)
    }

    @Test
    fun unchangedScoreAndRankReturnsNull() {
        val prev = mapOf("sf2" to scoreEntry(gameId = "sf2", score = 15000L, rank = 12L))
        val updated = listOf(scoreEntry(gameId = "sf2", score = 15000L, rank = 12L))

        assertNull(detectImprovedHighScore(prev, updated))
    }

    @Test
    fun worseRankWithoutHigherScoreReturnsNull() {
        val prev = mapOf("sf2" to scoreEntry(gameId = "sf2", score = 15000L, rank = 8L))
        val updated = listOf(scoreEntry(gameId = "sf2", score = 15000L, rank = 9L))

        assertNull(detectImprovedHighScore(prev, updated))
    }
}
