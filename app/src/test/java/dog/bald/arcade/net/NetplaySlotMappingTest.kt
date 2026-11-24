package dog.bald.arcade.net

import dog.bald.arcade.data.RoomSlotInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetplaySlotMappingTest {

    data class ResolvedNetplayHandles(
        val localHandle: Int,
        val numPlayers: Int,
        val peerHandles: List<Int>,
        val peerAddrs: List<String>
    )

    private fun resolveHandles(slotsJson: Map<String, RoomSlotInfo>, mySlot: Int): ResolvedNetplayHandles {
        val seatedEntries = slotsJson.entries
            .mapNotNull { entry -> entry.key.toIntOrNull()?.let { slotIdx -> slotIdx to entry.value } }
            .sortedBy { it.first }
        val totalPlayers = seatedEntries.size.coerceAtLeast(2)
        val myPlayerIndex = seatedEntries.indexOfFirst { it.first == mySlot }.takeIf { it >= 0 } ?: 0

        val peerHandles = mutableListOf<Int>()
        val peerAddrs = arrayListOf<String>()
        seatedEntries.forEachIndexed { handle, (_, slotInfo) ->
            if (handle != myPlayerIndex) {
                peerHandles.add(handle)
                peerAddrs.add(slotInfo.endpoint)
            }
        }
        return ResolvedNetplayHandles(myPlayerIndex, totalPlayers, peerHandles, peerAddrs)
    }

    @Test
    fun nonContiguousSlotsZeroAndTwoMapToContiguousHandlesZeroAndOne() {
        val slots = mapOf(
            "0" to RoomSlotInfo(userId = "u-host", username = "Host", endpoint = "1.2.3.4:7000", ready = true),
            "2" to RoomSlotInfo(userId = "u-guest", username = "Guest", endpoint = "5.6.7.8:7002", ready = true)
        )

        // Host side (slot 0)
        val hostRes = resolveHandles(slots, mySlot = 0)
        assertEquals(0, hostRes.localHandle)
        assertEquals(2, hostRes.numPlayers)
        assertEquals(listOf(1), hostRes.peerHandles)
        assertEquals(listOf("5.6.7.8:7002"), hostRes.peerAddrs)
        assertTrue(hostRes.localHandle < hostRes.numPlayers)
        assertTrue(hostRes.peerHandles.all { it < hostRes.numPlayers })

        // Guest side (slot 2)
        val guestRes = resolveHandles(slots, mySlot = 2)
        assertEquals(1, guestRes.localHandle)
        assertEquals(2, guestRes.numPlayers)
        assertEquals(listOf(0), guestRes.peerHandles)
        assertEquals(listOf("1.2.3.4:7000"), guestRes.peerAddrs)
        assertTrue(guestRes.localHandle < guestRes.numPlayers)
        assertTrue(guestRes.peerHandles.all { it < guestRes.numPlayers })
    }

    @Test
    fun nonContiguousSlotsOneAndThreeMapToContiguousHandles() {
        val slots = mapOf(
            "1" to RoomSlotInfo(userId = "p1", username = "P1", endpoint = "10.0.0.1:7001", ready = true),
            "3" to RoomSlotInfo(userId = "p3", username = "P3", endpoint = "10.0.0.3:7003", ready = true)
        )

        val res1 = resolveHandles(slots, mySlot = 1)
        assertEquals(0, res1.localHandle)
        assertEquals(2, res1.numPlayers)
        assertEquals(listOf(1), res1.peerHandles)

        val res3 = resolveHandles(slots, mySlot = 3)
        assertEquals(1, res3.localHandle)
        assertEquals(2, res3.numPlayers)
        assertEquals(listOf(0), res3.peerHandles)
    }

    @Test
    fun threePlayersInFourSlotRoomMapToZeroOneTwo() {
        val slots = mapOf(
            "0" to RoomSlotInfo(userId = "h", username = "H", endpoint = "ep0", ready = true),
            "2" to RoomSlotInfo(userId = "g1", username = "G1", endpoint = "ep2", ready = true),
            "3" to RoomSlotInfo(userId = "g2", username = "G2", endpoint = "ep3", ready = true)
        )

        val res0 = resolveHandles(slots, mySlot = 0)
        assertEquals(0, res0.localHandle)
        assertEquals(3, res0.numPlayers)
        assertEquals(listOf(1, 2), res0.peerHandles)
        assertEquals(listOf("ep2", "ep3"), res0.peerAddrs)

        val res2 = resolveHandles(slots, mySlot = 2)
        assertEquals(1, res2.localHandle)
        assertEquals(3, res2.numPlayers)
        assertEquals(listOf(0, 2), res2.peerHandles)
        assertEquals(listOf("ep0", "ep3"), res2.peerAddrs)

        val res3 = resolveHandles(slots, mySlot = 3)
        assertEquals(2, res3.localHandle)
        assertEquals(3, res3.numPlayers)
        assertEquals(listOf(0, 1), res3.peerHandles)
        assertEquals(listOf("ep0", "ep2"), res3.peerAddrs)
    }
}
