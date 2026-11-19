package dog.bald.arcade.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class DiscoveredLanRoom(
    val serviceName: String,
    val roomCode: String,
    val gameTitle: String,
    val hostIp: String,
    val udpPort: Int
)

class LanNsdManager(context: Context) {
    companion object {
        private const val SERVICE_TYPE = "_balddog-arcade._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredLanRoom>>(emptyList())
    val discoveredRooms: StateFlow<List<DiscoveredLanRoom>> = _discoveredRooms.asStateFlow()

    fun getLocalIpv4Address(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { iface ->
                if (!iface.isUp || iface.isLoopback) emptyList() else iface.inetAddresses.toList()
            }?.firstOrNull { addr ->
                addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress
            }?.hostAddress
        }.getOrNull()
    }

    fun registerRoom(roomCode: String, gameTitle: String, udpPort: Int) {
        unregisterRoom()
        val info = NsdServiceInfo().apply {
            serviceName = "BaldDog-$roomCode"
            serviceType = SERVICE_TYPE
            port = udpPort
            setAttribute("code", roomCode)
            setAttribute("game", gameTitle.take(32))
            setAttribute("udp", udpPort.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun unregisterRoom() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
    }

    fun startDiscovery() {
        stopDiscovery()
        acquireMulticastLock()
        _discoveredRooms.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_balddog-arcade")) return
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val ip = resolved.host?.hostAddress ?: return
                        val code = resolved.attributes["code"]?.decodeToString() ?: resolved.serviceName
                        val game = resolved.attributes["game"]?.decodeToString() ?: "Arcade"
                        val udp = resolved.attributes["udp"]?.decodeToString()?.toIntOrNull() ?: resolved.port
                        val item = DiscoveredLanRoom(
                            serviceName = resolved.serviceName,
                            roomCode = code,
                            gameTitle = game,
                            hostIp = ip,
                            udpPort = udp
                        )
                        val current = _discoveredRooms.value.filterNot { it.serviceName == item.serviceName }
                        _discoveredRooms.value = current + item
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discoveredRooms.value = _discoveredRooms.value.filterNot {
                    it.serviceName == serviceInfo.serviceName
                }
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("balddog_nsd_lock").apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }
}
