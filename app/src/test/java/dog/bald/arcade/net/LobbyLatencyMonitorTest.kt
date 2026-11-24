package dog.bald.arcade.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LobbyLatencyMonitorTest {

    @Test
    fun buildStunBindingRequestProducesValidRfc5389PacketAndUniqueTransactionIds() {
        val monitor = LobbyLatencyMonitor(
            localPort = 0,
            myUserId = "user-1",
            measureInternetPing = false
        )

        val (txKey1, req1) = monitor.buildStunBindingRequest()
        val (txKey2, req2) = monitor.buildStunBindingRequest()

        assertEquals(20, req1.size)
        assertEquals(0x00.toByte(), req1[0])
        assertEquals(0x01.toByte(), req1[1])
        assertEquals(0x00.toByte(), req1[2])
        assertEquals(0x00.toByte(), req1[3])
        assertEquals(0x21.toByte(), req1[4])
        assertEquals(0x12.toByte(), req1[5])
        assertEquals(0xA4.toByte(), req1[6])
        assertEquals(0x42.toByte(), req1[7])

        assertEquals(24, txKey1.length)
        val expectedHex1 = req1.sliceArray(8 until 20).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex1, txKey1)
        assertNotEquals(txKey1, txKey2)
    }

    @Test
    fun parentScopeCancellationCancelsMonitorJobAndClosesSocket() = runBlocking {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val monitor = LobbyLatencyMonitor(
            localPort = 0,
            myUserId = "user-1",
            measureInternetPing = false
        )

        try {
            monitor.start(parentScope)
            val job = monitor.activeJob
            assertNotNull(job)

            val deadlineMs = System.currentTimeMillis() + 3000L
            while (!monitor.isSocketOpen() && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(15L)
            }
            assertTrue("Expected UDP socket to bind and open", monitor.isSocketOpen())

            parentScope.cancel()

            withTimeout(3000L) {
                job!!.join()
            }
            assertTrue(job!!.isCancelled)
            assertTrue(job.isCompleted)
            assertFalse("Socket must be closed when parent scope cancels", monitor.isSocketOpen())
        } finally {
            monitor.close()
            parentScope.cancel()
        }
    }

    @Test
    fun parseXorMappedAddressExtractsValidIpv4Endpoint() {
        val monitor = LobbyLatencyMonitor(0, "u1", false)
        // 20 bytes STUN header + XOR-MAPPED-ADDRESS attr
        val port = 54321
        val xport = port xor 0x2112
        val ipBytes = byteArrayOf(203.toByte(), 0.toByte(), 113.toByte(), 42.toByte())
        val packet = byteArrayOf(
            0x01.toByte(), 0x01.toByte(), // STUN success response
            0x00.toByte(), 0x0C.toByte(), // Length = 12 bytes
            0x21.toByte(), 0x12.toByte(), 0xA4.toByte(), 0x42.toByte(), // Magic cookie
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, // TID
            0x00.toByte(), 0x20.toByte(), // XOR-MAPPED-ADDRESS
            0x00.toByte(), 0x08.toByte(), // attr length 8
            0x00.toByte(), 0x01.toByte(), // family IPv4
            (xport shr 8).toByte(), (xport and 0xFF).toByte(),
            (ipBytes[0].toInt() xor 0x21).toByte(),
            (ipBytes[1].toInt() xor 0x12).toByte(),
            (ipBytes[2].toInt() xor 0xA4).toByte(),
            (ipBytes[3].toInt() xor 0x42).toByte()
        )
        val endpoint = monitor.parseXorMappedAddress(packet, packet.size)
        assertEquals("203.0.113.42:54321", endpoint)
    }

    @Test
    fun parseXorMappedAddressExtractsClassicMappedAddress() {
        val monitor = LobbyLatencyMonitor(0, "u1", false)
        val port = 49152
        val ipBytes = byteArrayOf(198.toByte(), 51.toByte(), 100.toByte(), 77.toByte())
        val packet = byteArrayOf(
            0x01.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x0C.toByte(),
            0x21.toByte(), 0x12.toByte(), 0xA4.toByte(), 0x42.toByte(),
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            0x00.toByte(), 0x01.toByte(), // MAPPED-ADDRESS
            0x00.toByte(), 0x08.toByte(),
            0x00.toByte(), 0x01.toByte(),
            (port shr 8).toByte(), (port and 0xFF).toByte(),
            ipBytes[0], ipBytes[1], ipBytes[2], ipBytes[3]
        )
        val endpoint = monitor.parseXorMappedAddress(packet, packet.size)
        assertEquals("198.51.100.77:49152", endpoint)
    }
}
