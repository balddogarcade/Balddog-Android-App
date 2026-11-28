package dog.bald.arcade.net

import dog.bald.arcade.data.RoomDto
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe one-shot gate ensuring a single matchmaking transition is processed
 * even if Realtime and fallback polling emit the matched room concurrently.
 */
class MatchmakingTransitionGate {
    private val consumed = AtomicBoolean(false)

    fun tryConsume(isCurrentlySearching: Boolean): Boolean {
        if (!isCurrentlySearching) return false
        return consumed.compareAndSet(false, true)
    }

    fun reset() {
        consumed.set(false)
    }
}

sealed class RoomJoinEligibility {
    data object Eligible : RoomJoinEligibility()

    data class Ineligible(
        val reasonCode: String,
        val userMessage: String,
    ) : RoomJoinEligibility()
}

object MatchmakingPolicy {
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun parseRoomIdFromUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val trimmed = uriString.trim()
        val parsed = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
        val segments =
            (parsed.path ?: "")
                .split('/')
                .filter { it.isNotBlank() }

        val candidate =
            when {
                (scheme == "https" || scheme == "http") &&
                    (host == "bald.dog" || host == "www.bald.dog") &&
                    segments.size == 2 &&
                    segments[0] == "room" -> segments[1]

                scheme == "dog.bald.arcade" &&
                    host == "room" &&
                    segments.size == 1 -> segments[0]

                scheme == "balddog" &&
                    host == "room" &&
                    segments.size == 1 -> segments[0]

                else -> null
            }

        return candidate?.takeIf { UUID_REGEX.matches(it) }
    }

    fun evaluateRoomJoinEligibility(
        room: RoomDto?,
        userId: String,
    ): RoomJoinEligibility {
        if (room == null) {
            return RoomJoinEligibility.Ineligible(
                reasonCode = "not_found",
                userMessage = "La sala ya no existe o fue cerrada.",
            )
        }
        if (room.status != "waiting") {
            val msg =
                if (room.status == "playing") {
                    "Esta sala ya inició la partida."
                } else {
                    "Esta sala ya fue cerrada."
                }
            return RoomJoinEligibility.Ineligible(
                reasonCode = "not_waiting_${room.status}",
                userMessage = msg,
            )
        }
        if (room.bannedUserIds.any { it.equals(userId, ignoreCase = true) }) {
            return RoomJoinEligibility.Ineligible(
                reasonCode = "banned",
                userMessage = "Fuiste baneado de esta sala por el anfitrión.",
            )
        }
        val alreadySeated = room.slotsJson.values.any { it.userId.equals(userId, ignoreCase = true) }
        if (!alreadySeated && room.slotsJson.size >= room.maxPlayers) {
            return RoomJoinEligibility.Ineligible(
                reasonCode = "full",
                userMessage = "La sala ya está llena (${room.slotsJson.size}/${room.maxPlayers}).",
            )
        }
        return RoomJoinEligibility.Eligible
    }

    fun formatElapsedMmSs(elapsedSeconds: Long): String {
        val safe = elapsedSeconds.coerceAtLeast(0L)
        val minutes = safe / 60L
        val seconds = safe % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
