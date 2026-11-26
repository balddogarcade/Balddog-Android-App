package dog.bald.arcade.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class LobbyPeerTarget(
    val userId: String,
    val endpoint: String,
)

/**
 * Mide la latencia UDP (RTT en ms) entre el jugador local y los pares de la sala
 * mientras están en el lobby, usando el mismo puerto UDP que luego usará GGRS.
 */
class LobbyLatencyMonitor(
    private val localPort: Int,
    private val myUserId: String,
    private val measureInternetPing: Boolean = false,
) {
    private val _latenciesMs = MutableStateFlow<Map<String, Int>>(emptyMap())
    val latenciesMs: StateFlow<Map<String, Int>> = _latenciesMs.asStateFlow()

    private val _publicEndpoint = MutableStateFlow<String?>(null)
    val publicEndpoint: StateFlow<String?> = _publicEndpoint.asStateFlow()

    @Volatile
    private var peers: List<LobbyPeerTarget> = emptyList()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var closed = false

    private val lastSeenNanos = ConcurrentHashMap<String, Long>()
    private val currentLatencies = ConcurrentHashMap<String, Int>()
    private val pendingStunPings = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()
    private val socketLock = Any()
    private var scopeJob: Job? = null

    internal val activeJob: Job?
        get() = scopeJob

    internal fun isSocketOpen(): Boolean =
        synchronized(socketLock) {
            socket?.let { !it.isClosed } == true
        }

    private fun closeSocket() {
        val toClose =
            synchronized(socketLock) {
                socket.also { socket = null }
            }
        runCatching { toClose?.close() }
    }

    fun updatePeers(newPeers: List<LobbyPeerTarget>) {
        peers = newPeers
        val activeIds = newPeers.mapTo(HashSet()) { it.userId }
        var changed = false
        currentLatencies.keys.toList().forEach { uid ->
            if (uid != myUserId && uid !in activeIds) {
                currentLatencies.remove(uid)
                lastSeenNanos.remove(uid)
                changed = true
            }
        }
        if (changed) {
            _latenciesMs.value = currentLatencies.toMap()
        }
    }

    fun start(parentScope: CoroutineScope) {
        if (closed || scopeJob != null) return
        val job = SupervisorJob(parentScope.coroutineContext[Job])
        scopeJob = job
        job.invokeOnCompletion {
            closeSocket()
        }
        val ioScope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + job)

        ioScope.launch {
            val sock =
                runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = 400
                        bind(InetSocketAddress(localPort))
                    }
                }.getOrNull() ?: return@launch

            synchronized(socketLock) {
                if (closed || !job.isActive) {
                    runCatching { sock.close() }
                    return@launch
                }
                socket = sock
            }

            val recvJob =
                launch {
                    val buf = ByteArray(256)
                    val packet = DatagramPacket(buf, buf.size)
                    while (isActive && !closed && !sock.isClosed) {
                        try {
                            packet.length = buf.size
                            sock.receive(packet)
                            handlePacket(sock, packet.socketAddress, packet.data, packet.length)
                        } catch (_: SocketTimeoutException) {
                            // Timeout periódico para evaluar cancelación
                        } catch (_: Throwable) {
                            if (closed || sock.isClosed) break
                        }
                    }
                }

            val sendJob =
                launch {
                    var stunTarget: InetSocketAddress? = null
                    while (isActive && !closed && !sock.isClosed) {
                        val now = System.nanoTime()
                        if (measureInternetPing && myUserId.isNotBlank()) {
                            if (stunTarget == null) {
                                stunTarget = resolveFirstStunTarget()
                            }
                            stunTarget?.let { target ->
                                val (txKey, reqBytes) = buildStunBindingRequest()
                                pendingStunPings[txKey] = now
                                if (pendingStunPings.size > 16) {
                                    pendingStunPings.entries.removeIf { now - it.value > 10_000_000_000L }
                                }
                                runCatching {
                                    sock.send(DatagramPacket(reqBytes, reqBytes.size, target))
                                }
                            }
                        }
                        val myPubIp = _publicEndpoint.value?.substringBefore('|')?.substringBefore(':')
                        for (peer in peers) {
                            if (peer.userId == myUserId) continue
                            val effectiveEp = NetplayEndpointResolver.resolveEffectiveEndpoint(peer.endpoint, myPubIp)
                            val addr = parseEndpoint(effectiveEp) ?: continue
                            val payload = "BDPING|$myUserId|${peer.userId}|$now".toByteArray(Charsets.UTF_8)
                            runCatching {
                                sock.send(DatagramPacket(payload, payload.size, addr))
                            }
                        }

                        // Limpiar mediciones obsoletas (> 6 s sin respuesta)
                        var staleRemoved = false
                        lastSeenNanos.forEach { (uid, seenAt) ->
                            if (now - seenAt > 6_000_000_000L) {
                                if (currentLatencies.remove(uid) != null) {
                                    staleRemoved = true
                                }
                                lastSeenNanos.remove(uid)
                            }
                        }
                        if (staleRemoved) {
                            _latenciesMs.value = currentLatencies.toMap()
                        }

                        delay(1500)
                    }
                }

            recvJob.join()
            sendJob.join()
        }
    }

    private fun handlePacket(
        sock: DatagramSocket,
        senderAddr: java.net.SocketAddress,
        data: ByteArray,
        length: Int,
    ) {
        if (length >= 20 &&
            data[0] == 0x01.toByte() &&
            data[1] == 0x01.toByte() &&
            data[4] == 0x21.toByte() &&
            data[5] == 0x12.toByte() &&
            data[6] == 0xA4.toByte() &&
            data[7] == 0x42.toByte()
        ) {
            val mappedEp = parseXorMappedAddress(data, length)
            if (mappedEp != null && _publicEndpoint.value != mappedEp) {
                _publicEndpoint.value = mappedEp
            }
            val txKey = data.sliceArray(8 until 20).joinToString("") { "%02x".format(it) }
            val sentNanos = pendingStunPings.remove(txKey) ?: return
            val now = System.nanoTime()
            val rttMs = ((now - sentNanos) / 1_000_000L).coerceIn(1L, 9999L).toInt()
            currentLatencies[myUserId] = rttMs
            lastSeenNanos[myUserId] = now
            _latenciesMs.value = currentLatencies.toMap()
            return
        }

        val msg = String(data, 0, length, Charsets.UTF_8)
        val parts = msg.split('|')
        if (parts.size != 4) return
        val type = parts[0]
        val senderUid = parts[1]
        val targetUid = parts[2]
        val sentNanos = parts[3].toLongOrNull() ?: return

        when (type) {
            "BDPING" -> {
                val pong = "BDPONG|$senderUid|$targetUid|$sentNanos".toByteArray(Charsets.UTF_8)
                runCatching {
                    sock.send(DatagramPacket(pong, pong.size, senderAddr))
                }
            }
            "BDPONG" -> {
                if (senderUid != myUserId) return
                val now = System.nanoTime()
                val rttMs = ((now - sentNanos) / 1_000_000L).coerceIn(1L, 9999L).toInt()
                currentLatencies[targetUid] = rttMs
                lastSeenNanos[targetUid] = now
                _latenciesMs.value = currentLatencies.toMap()
            }
        }
    }

    private fun resolveFirstStunTarget(): InetSocketAddress? {
        val servers = listOf("stun.cloudflare.com" to 3478, "stun.l.google.com" to 19302)
        for ((host, port) in servers) {
            val addr = runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
            if (addr != null && !addr.isUnresolved) return addr
        }
        return null
    }

    internal fun parseXorMappedAddress(
        data: ByteArray,
        length: Int,
    ): String? {
        if (length < 20) return null
        val magic0 = 0x21.toByte()
        val magic1 = 0x12.toByte()
        val magic2 = 0xA4.toByte()
        val magic3 = 0x42.toByte()
        if (data[4] != magic0 || data[5] != magic1 || data[6] != magic2 || data[7] != magic3) return null

        val msgLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val end = minOf(20 + msgLen, length)
        var pos = 20
        while (pos + 4 <= end) {
            val attrType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val attrLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            val valStart = pos + 4
            val valEnd = valStart + attrLen
            if (valEnd > end) break

            // 0x0020 = XOR-MAPPED-ADDRESS
            if (attrType == 0x0020 && attrLen >= 8) {
                val family = data[valStart + 1].toInt() and 0xFF
                val xport = (((data[valStart + 2].toInt() and 0xFF) shl 8) or (data[valStart + 3].toInt() and 0xFF)) xor 0x2112
                if (family == 0x01) { // IPv4
                    val b0 = (data[valStart + 4].toInt() and 0xFF) xor (magic0.toInt() and 0xFF)
                    val b1 = (data[valStart + 5].toInt() and 0xFF) xor (magic1.toInt() and 0xFF)
                    val b2 = (data[valStart + 6].toInt() and 0xFF) xor (magic2.toInt() and 0xFF)
                    val b3 = (data[valStart + 7].toInt() and 0xFF) xor (magic3.toInt() and 0xFF)
                    return "$b0.$b1.$b2.$b3:$xport"
                }
            } else if (attrType == 0x0001 && attrLen >= 8) { // MAPPED-ADDRESS (fallback clásico)
                val family = data[valStart + 1].toInt() and 0xFF
                val port = ((data[valStart + 2].toInt() and 0xFF) shl 8) or (data[valStart + 3].toInt() and 0xFF)
                if (family == 0x01) {
                    val b0 = data[valStart + 4].toInt() and 0xFF
                    val b1 = data[valStart + 5].toInt() and 0xFF
                    val b2 = data[valStart + 6].toInt() and 0xFF
                    val b3 = data[valStart + 7].toInt() and 0xFF
                    return "$b0.$b1.$b2.$b3:$port"
                }
            }
            val padded = (attrLen + 3) and 3.inv()
            pos = valStart + padded
        }
        return null
    }

    internal fun buildStunBindingRequest(): Pair<String, ByteArray> {
        val req = ByteArray(20)
        req[0] = 0x00
        req[1] = 0x01
        req[4] = 0x21
        req[5] = 0x12
        req[6] = 0xA4.toByte()
        req[7] = 0x42
        val tid = ByteArray(12).also { secureRandom.nextBytes(it) }
        System.arraycopy(tid, 0, req, 8, 12)
        val txKey = tid.joinToString("") { "%02x".format(it) }
        return txKey to req
    }

    private fun parseEndpoint(endpoint: String): InetSocketAddress? {
        val idx = endpoint.lastIndexOf(':')
        if (idx <= 0 || idx >= endpoint.length - 1) return null
        val host = endpoint.substring(0, idx).trim().removeSurrounding("[", "]")
        val port = endpoint.substring(idx + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return runCatching {
            InetSocketAddress(InetAddress.getByName(host), port)
        }.getOrNull()
    }

    fun close() {
        if (closed) return
        closed = true
        scopeJob?.cancel()
        scopeJob = null
        closeSocket()
    }
}
