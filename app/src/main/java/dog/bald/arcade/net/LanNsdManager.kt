package dog.bald.arcade.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface

data class DiscoveredLanRoom(
    val serviceName: String,
    val roomCode: String,
    val gameTitle: String,
    val hostIp: String,
    val udpPort: Int,
)

class LanNsdManager internal constructor(
    private val gateway: NsdGateway,
) {
    constructor(context: Context) : this(AndroidNsdGateway(context))

    companion object {
        private const val SERVICE_TYPE = "_balddog-arcade._tcp."
    }

    internal interface NsdGateway {
        fun registerService(
            info: NsdServiceInfo,
            listener: NsdManager.RegistrationListener,
        )

        fun unregisterService(listener: NsdManager.RegistrationListener)

        fun discoverServices(
            serviceType: String,
            listener: NsdManager.DiscoveryListener,
        )

        fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener)

        fun resolveService(
            serviceInfo: NsdServiceInfo,
            listener: NsdManager.ResolveListener,
        )

        fun acquireMulticastLock()

        fun releaseMulticastLock()

        fun getServiceType(info: NsdServiceInfo): String?

        fun getServiceName(info: NsdServiceInfo): String?

        fun extractDiscoveredRoom(resolved: NsdServiceInfo): DiscoveredLanRoom?
    }

    private class AndroidNsdGateway(
        context: Context,
    ) : NsdGateway {
        private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        private var multicastLock: WifiManager.MulticastLock? = null

        override fun registerService(
            info: NsdServiceInfo,
            listener: NsdManager.RegistrationListener,
        ) {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        override fun unregisterService(listener: NsdManager.RegistrationListener) {
            nsdManager.unregisterService(listener)
        }

        override fun discoverServices(
            serviceType: String,
            listener: NsdManager.DiscoveryListener,
        ) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        override fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
            nsdManager.stopServiceDiscovery(listener)
        }

        override fun resolveService(
            serviceInfo: NsdServiceInfo,
            listener: NsdManager.ResolveListener,
        ) {
            nsdManager.resolveService(serviceInfo, listener)
        }

        override fun acquireMulticastLock() {
            runCatching {
                if (multicastLock == null) {
                    multicastLock =
                        wifiManager.createMulticastLock("balddog_nsd_lock").apply {
                            setReferenceCounted(false)
                        }
                }
                multicastLock?.acquire()
            }
        }

        override fun releaseMulticastLock() {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            }
        }

        override fun getServiceType(info: NsdServiceInfo): String? = info.serviceType

        override fun getServiceName(info: NsdServiceInfo): String? = info.serviceName

        override fun extractDiscoveredRoom(resolved: NsdServiceInfo): DiscoveredLanRoom? {
            val ip = resolved.host?.hostAddress ?: return null
            val code = resolved.attributes["code"]?.decodeToString() ?: resolved.serviceName
            val game = resolved.attributes["game"]?.decodeToString() ?: "Arcade"
            val udp = resolved.attributes["udp"]?.decodeToString()?.toIntOrNull() ?: resolved.port
            return DiscoveredLanRoom(
                serviceName = resolved.serviceName,
                roomCode = code,
                gameTitle = game,
                hostIp = ip,
                udpPort = udp,
            )
        }
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveLock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var activeResolveServiceName: String? = null
    private var discoveryGeneration = 0L

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredLanRoom>>(emptyList())
    val discoveredRooms: StateFlow<List<DiscoveredLanRoom>> = _discoveredRooms.asStateFlow()

    fun getLocalIpv4Address(): String? =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                ?.flatMap { iface ->
                    if (!iface.isUp || iface.isLoopback) emptyList() else iface.inetAddresses.toList()
                }?.firstOrNull { addr ->
                    addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress
                }?.hostAddress
        }.getOrNull()

    fun registerRoom(
        roomCode: String,
        gameTitle: String,
        udpPort: Int,
    ) {
        unregisterRoom()
        val info =
            NsdServiceInfo().apply {
                serviceName = "BaldDog-$roomCode"
                serviceType = SERVICE_TYPE
                port = udpPort
                setAttribute("code", roomCode)
                setAttribute("game", gameTitle.take(32))
                setAttribute("udp", udpPort.toString())
            }

        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}

                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {}

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {}
            }
        registrationListener = listener
        runCatching { gateway.registerService(info, listener) }
    }

    fun unregisterRoom() {
        registrationListener?.let { listener ->
            runCatching { gateway.unregisterService(listener) }
        }
        registrationListener = null
    }

    fun startDiscovery() {
        stopDiscovery()
        gateway.acquireMulticastLock()
        val gen =
            synchronized(resolveLock) {
                val nextGen = ++discoveryGeneration
                resolveQueue.clear()
                resolving = false
                activeResolveServiceName = null
                _discoveredRooms.update { emptyList() }
                nextGen
            }

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    stopDiscovery()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val type = gateway.getServiceType(serviceInfo) ?: return
                    if (!type.contains("_balddog-arcade")) return
                    val foundName = gateway.getServiceName(serviceInfo)
                    val shouldDispatch =
                        synchronized(resolveLock) {
                            if (gen != discoveryGeneration) return
                            val alreadyPending =
                                foundName != null &&
                                    (
                                        foundName == activeResolveServiceName ||
                                            resolveQueue.any { gateway.getServiceName(it) == foundName }
                                    )
                            if (!alreadyPending) {
                                resolveQueue.addLast(serviceInfo)
                            }
                            !resolving && resolveQueue.isNotEmpty()
                        }
                    if (shouldDispatch) {
                        resolveNext(gen)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val lostName = gateway.getServiceName(serviceInfo) ?: return
                    synchronized(resolveLock) {
                        if (gen != discoveryGeneration) return
                        resolveQueue.removeAll { gateway.getServiceName(it) == lostName }
                        _discoveredRooms.update { current ->
                            current.filterNot { it.serviceName == lostName }
                        }
                    }
                }
            }
        discoveryListener = listener
        runCatching { gateway.discoverServices(SERVICE_TYPE, listener) }
    }

    private fun resolveNext(gen: Long) {
        while (true) {
            val next =
                synchronized(resolveLock) {
                    if (gen != discoveryGeneration || resolving) {
                        return
                    }
                    val item = resolveQueue.removeFirstOrNull() ?: return
                    resolving = true
                    activeResolveServiceName = gateway.getServiceName(item)
                    item
                }

            val resolveListener =
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        info: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        var shouldContinue = false
                        synchronized(resolveLock) {
                            if (gen == discoveryGeneration) {
                                resolving = false
                                activeResolveServiceName = null
                                shouldContinue = resolveQueue.isNotEmpty()
                            }
                        }
                        if (shouldContinue) {
                            resolveNext(gen)
                        }
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val item = gateway.extractDiscoveredRoom(resolved)
                        var shouldContinue = false
                        synchronized(resolveLock) {
                            if (gen == discoveryGeneration) {
                                if (item != null) {
                                    _discoveredRooms.update { current ->
                                        current.filterNot { it.serviceName == item.serviceName } + item
                                    }
                                }
                                resolving = false
                                activeResolveServiceName = null
                                shouldContinue = resolveQueue.isNotEmpty()
                            }
                        }
                        if (shouldContinue) {
                            resolveNext(gen)
                        }
                    }
                }

            val dispatched =
                try {
                    gateway.resolveService(next, resolveListener)
                    true
                } catch (_: Throwable) {
                    false
                }

            if (dispatched) {
                return
            }

            val hasMore =
                synchronized(resolveLock) {
                    if (gen == discoveryGeneration) {
                        resolving = false
                        activeResolveServiceName = null
                        resolveQueue.isNotEmpty()
                    } else {
                        false
                    }
                }
            if (!hasMore) {
                return
            }
        }
    }

    fun stopDiscovery() {
        val listenerToStop: NsdManager.DiscoveryListener?
        synchronized(resolveLock) {
            discoveryGeneration++
            resolveQueue.clear()
            resolving = false
            activeResolveServiceName = null
            listenerToStop = discoveryListener
            discoveryListener = null
        }
        listenerToStop?.let { listener ->
            runCatching { gateway.stopServiceDiscovery(listener) }
        }
        gateway.releaseMulticastLock()
    }
}
