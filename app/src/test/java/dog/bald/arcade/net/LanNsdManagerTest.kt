package dog.bald.arcade.net

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class LanNsdManagerTest {

    private data class ServiceMeta(
        val serviceType: String,
        val serviceName: String,
        val room: DiscoveredLanRoom?
    )

    private class FakeNsdGateway : LanNsdManager.NsdGateway {
        private val metadata = IdentityHashMap<NsdServiceInfo, ServiceMeta>()
        var discoveryListener: NsdManager.DiscoveryListener? = null
        val resolveCalls = mutableListOf<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
        var throwOnResolveServiceNames: MutableSet<String> = mutableSetOf()
        var multicastAcquired = false
        var multicastReleased = false

        fun createService(
            serviceName: String,
            room: DiscoveredLanRoom? = DiscoveredLanRoom(
                serviceName = serviceName,
                roomCode = serviceName.removePrefix("BaldDog-"),
                gameTitle = "Metal Slug",
                hostIp = "192.168.1.50",
                udpPort = 7000
            ),
            serviceType: String = "_balddog-arcade._tcp."
        ): NsdServiceInfo {
            return NsdServiceInfo().also {
                metadata[it] = ServiceMeta(serviceType, serviceName, room)
            }
        }

        override fun registerService(info: NsdServiceInfo, listener: NsdManager.RegistrationListener) = Unit
        override fun unregisterService(listener: NsdManager.RegistrationListener) = Unit

        override fun discoverServices(serviceType: String, listener: NsdManager.DiscoveryListener) {
            discoveryListener = listener
        }

        override fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
            if (discoveryListener === listener) {
                discoveryListener = null
            }
        }

        override fun resolveService(serviceInfo: NsdServiceInfo, listener: NsdManager.ResolveListener) {
            val name = metadata[serviceInfo]?.serviceName
            if (name != null && throwOnResolveServiceNames.remove(name)) {
                throw IllegalStateException("FAILURE_ALREADY_ACTIVE")
            }
            resolveCalls += serviceInfo to listener
        }

        override fun acquireMulticastLock() {
            multicastAcquired = true
        }

        override fun releaseMulticastLock() {
            multicastReleased = true
        }

        override fun getServiceType(info: NsdServiceInfo): String? = metadata[info]?.serviceType
        override fun getServiceName(info: NsdServiceInfo): String? = metadata[info]?.serviceName
        override fun extractDiscoveredRoom(resolved: NsdServiceInfo): DiscoveredLanRoom? = metadata[resolved]?.room
    }

    @Test
    fun onServiceFoundSerializesResolveCallsAndDeduplicatesSameServiceName() {
        val gateway = FakeNsdGateway()
        val manager = LanNsdManager(gateway)

        manager.startDiscovery()
        val listener = gateway.discoveryListener!!

        val roomA1 = gateway.createService("BaldDog-AAAA")
        val roomA2Dup = gateway.createService("BaldDog-AAAA")
        val roomB = gateway.createService(
            "BaldDog-BBBB",
            DiscoveredLanRoom("BaldDog-BBBB", "BBBB", "Garou", "192.168.1.51", 7001)
        )

        listener.onServiceFound(roomA1)
        listener.onServiceFound(roomA2Dup)
        listener.onServiceFound(roomB)

        // Only the first service should start resolving immediately (API < 34 safety)
        assertEquals(1, gateway.resolveCalls.size)
        assertEquals("BaldDog-AAAA", gateway.getServiceName(gateway.resolveCalls[0].first))

        // Complete first resolve -> triggers second resolve and updates discoveredRooms
        gateway.resolveCalls[0].second.onServiceResolved(roomA1)
        assertEquals(listOf("BaldDog-AAAA"), manager.discoveredRooms.value.map { it.serviceName })
        assertEquals(2, gateway.resolveCalls.size)
        assertEquals("BaldDog-BBBB", gateway.getServiceName(gateway.resolveCalls[1].first))

        // Complete second resolve -> duplicate A2 was deduplicated so queue is now empty
        gateway.resolveCalls[1].second.onServiceResolved(roomB)
        assertEquals(2, gateway.resolveCalls.size)
        assertEquals(listOf("BaldDog-AAAA", "BaldDog-BBBB"), manager.discoveredRooms.value.map { it.serviceName })
    }

    @Test
    fun synchronousExceptionInResolveServiceAdvancesQueueWithoutDeadlock() {
        val gateway = FakeNsdGateway()
        gateway.throwOnResolveServiceNames.add("BaldDog-FAIL")
        val manager = LanNsdManager(gateway)

        manager.startDiscovery()
        val listener = gateway.discoveryListener!!

        val failSvc = gateway.createService("BaldDog-FAIL")
        val okSvc = gateway.createService("BaldDog-OKOK")

        listener.onServiceFound(failSvc)
        listener.onServiceFound(okSvc)

        // First service threw synchronously inside resolveService, so queue should have fallen through to okSvc
        assertEquals(1, gateway.resolveCalls.size)
        assertEquals("BaldDog-OKOK", gateway.getServiceName(gateway.resolveCalls[0].first))

        gateway.resolveCalls[0].second.onServiceResolved(okSvc)
        assertEquals(listOf("BaldDog-OKOK"), manager.discoveredRooms.value.map { it.serviceName })
    }

    @Test
    fun staleCallbacksFromPreviousDiscoveryGenerationAreIgnored() {
        val gateway = FakeNsdGateway()
        val manager = LanNsdManager(gateway)

        manager.startDiscovery()
        val gen1Listener = gateway.discoveryListener!!
        val oldRoom = gateway.createService("BaldDog-OLD1")
        gen1Listener.onServiceFound(oldRoom)
        assertEquals(1, gateway.resolveCalls.size)
        val staleResolveListener = gateway.resolveCalls[0].second

        // Restart discovery -> increments generation and clears queue/resolving
        manager.startDiscovery()
        val gen2Listener = gateway.discoveryListener!!
        val newRoom = gateway.createService("BaldDog-NEW2")
        val queuedNewRoom = gateway.createService("BaldDog-NEW3")
        gen2Listener.onServiceFound(newRoom)
        gen2Listener.onServiceFound(queuedNewRoom)
        assertEquals(2, gateway.resolveCalls.size)

        // Stale generation-1 callback completes late: must NOT add oldRoom or start resolving queuedNewRoom early
        staleResolveListener.onServiceResolved(oldRoom)
        assertTrue(manager.discoveredRooms.value.isEmpty())
        assertEquals(2, gateway.resolveCalls.size)

        // Stale failure callback must also be ignored
        staleResolveListener.onResolveFailed(oldRoom, NsdManager.FAILURE_INTERNAL_ERROR)
        assertEquals(2, gateway.resolveCalls.size)

        // Current generation resolves normally
        gateway.resolveCalls[1].second.onServiceResolved(newRoom)
        assertEquals(listOf("BaldDog-NEW2"), manager.discoveredRooms.value.map { it.serviceName })
        assertEquals(3, gateway.resolveCalls.size)
        assertEquals("BaldDog-NEW3", gateway.getServiceName(gateway.resolveCalls[2].first))
    }

    @Test
    fun onServiceLostRemovesDiscoveredAndPendingRooms() {
        val gateway = FakeNsdGateway()
        val manager = LanNsdManager(gateway)

        manager.startDiscovery()
        val listener = gateway.discoveryListener!!

        val roomA = gateway.createService("BaldDog-AAAA")
        val roomB = gateway.createService("BaldDog-BBBB")
        val roomC = gateway.createService("BaldDog-CCCC")

        listener.onServiceFound(roomA)
        listener.onServiceFound(roomB)
        listener.onServiceFound(roomC)

        gateway.resolveCalls[0].second.onServiceResolved(roomA)
        assertEquals(listOf("BaldDog-AAAA"), manager.discoveredRooms.value.map { it.serviceName })

        // Lose roomA (already discovered) and roomC (queued)
        listener.onServiceLost(roomA)
        listener.onServiceLost(roomC)
        assertTrue(manager.discoveredRooms.value.isEmpty())

        // Resolve roomB; roomC should have been removed from the queue
        gateway.resolveCalls[1].second.onServiceResolved(roomB)
        assertEquals(listOf("BaldDog-BBBB"), manager.discoveredRooms.value.map { it.serviceName })
        assertEquals(2, gateway.resolveCalls.size)
    }
}
