package dog.bald.arcade.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArcadeRankTest {

    @Test
    fun testArcadeRankProgressionAndThresholds() {
        assertEquals(ArcadeRank.NOVATO, ArcadeRank.fromPoints(0L))
        assertEquals(ArcadeRank.NOVATO, ArcadeRank.fromPoints(149L))

        assertEquals(ArcadeRank.RETADOR, ArcadeRank.fromPoints(150L))
        assertEquals(ArcadeRank.RETADOR, ArcadeRank.fromPoints(449L))

        assertEquals(ArcadeRank.LUCHADOR, ArcadeRank.fromPoints(450L))
        assertEquals(ArcadeRank.LUCHADOR, ArcadeRank.fromPoints(899L))

        assertEquals(ArcadeRank.MAESTRO, ArcadeRank.fromPoints(900L))
        assertEquals(ArcadeRank.MAESTRO, ArcadeRank.fromPoints(1499L))

        assertEquals(ArcadeRank.GRAN_MAESTRO, ArcadeRank.fromPoints(1500L))
        assertEquals(ArcadeRank.GRAN_MAESTRO, ArcadeRank.fromPoints(1999L))

        assertEquals(ArcadeRank.LEYENDA, ArcadeRank.fromPoints(2000L))
        assertEquals(ArcadeRank.LEYENDA, ArcadeRank.fromPoints(2200L))
        assertEquals(ArcadeRank.LEYENDA, ArcadeRank.fromPoints(10000L))
    }
}
