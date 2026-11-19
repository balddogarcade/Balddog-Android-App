package dog.bald.arcade.data

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ArcadeRepository(context: Context) {
    private val client = SupabaseClientProvider.get(context)

    fun isLoggedIn(): Boolean = client.auth.currentSessionOrNull() != null

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    suspend fun restoreSession(): Boolean {
        return runCatching {
            client.auth.loadFromStorage()
            client.auth.currentSessionOrNull() != null
        }.getOrDefault(false)
    }

    suspend fun signInAnonymously() {
        client.auth.signInAnonymously()
    }

    suspend fun signInWithEmail(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUpWithEmail(email: String, password: String, username: String) {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("username", username.trim())
            }
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    suspend fun fetchGames(): List<GameDto> {
        return client.from("games").select().decodeList<GameDto>()
    }

    suspend fun fetchBios(): List<GameBiosDto> {
        return client.from("game_bios").select().decodeList<GameBiosDto>()
    }

    suspend fun fetchOpenRooms(): List<RoomDto> {
        return client.from("rooms").select {
            filter {
                eq("status", "waiting")
            }
        }.decodeList<RoomDto>()
    }

    suspend fun fetchMyProfile(): ProfileDto? {
        val uid = currentUserId() ?: return null
        return client.from("profiles").select {
            filter {
                eq("id", uid)
            }
        }.decodeSingleOrNull<ProfileDto>()
    }
}
