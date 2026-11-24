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
}
