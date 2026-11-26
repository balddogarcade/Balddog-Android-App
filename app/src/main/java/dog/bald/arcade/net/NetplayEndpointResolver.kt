package dog.bald.arcade.net

/**
 * Resuelve endpoints efectivos para P2P Netplay emulando selección de candidatos ICE:
 * - Si dos pares están en redes distintas, utiliza su dirección pública STUN (WAN).
 * - Si dos pares comparten la misma dirección IP pública (ambos en la misma Wi-Fi / NAT),
 *   utiliza el candidato LAN privado para evitar el descarte de paquetes por falta de
 *   soporte de NAT Hairpinning (NAT Loopback) en routers hogareños.
 */
object NetplayEndpointResolver {
    /**
     * Da formato a un endpoint de slot combinando el endpoint público STUN y el candidato LAN:
     * Ej: "186.156.23.107:7059|192.168.0.3:7059"
     */
    fun formatCandidateEndpoint(
        publicEp: String,
        localIp: String?,
        port: Int,
    ): String {
        val cleanPub = publicEp.substringBefore('|').trim()
        val lanCandidate = if (!localIp.isNullOrBlank()) "$localIp:$port" else null
        return if (lanCandidate != null && cleanPub.isNotBlank() && cleanPub != lanCandidate) {
            "$cleanPub|$lanCandidate"
        } else {
            cleanPub.ifBlank { lanCandidate ?: "127.0.0.1:$port" }
        }
    }

    /**
     * Extrae el endpoint al que este cliente debe enviar paquetes UDP:
     * - Si [peerEndpoint] contiene un candidato LAN y la IP pública del par coincide con [myPublicIp],
     *   se retorna el candidato LAN.
     * - En caso contrario (pares remotos en redes distintas o formato clásico sin candidato LAN),
     *   se retorna el endpoint público.
     */
    fun resolveEffectiveEndpoint(
        peerEndpoint: String,
        myPublicIp: String?,
    ): String {
        val trimmed = peerEndpoint.trim()
        if (!trimmed.contains('|')) {
            return trimmed
        }
        val parts = trimmed.split('|')
        val publicEp = parts[0].trim()
        val lanEp = parts.getOrNull(1)?.trim()

        val peerHost = publicEp.substringBefore(':').trim().removeSurrounding("[", "]")
        val myHost =
            myPublicIp
                ?.substringBefore('|')
                ?.substringBefore(':')
                ?.trim()
                ?.removeSurrounding("[", "]")

        if (!myHost.isNullOrBlank() && peerHost == myHost && !lanEp.isNullOrBlank()) {
            return lanEp
        }
        return publicEp
    }

    /**
     * Verifica si un endpoint es puramente privado (sin candidato público válido).
     */
    fun isPrivateEndpoint(endpoint: String): Boolean {
        val pub = endpoint.substringBefore('|').trim()
        val host = pub.substringBefore(':').trim().removeSurrounding("[", "]")
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.substringAfter('.').substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
