package dog.bald.arcade.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dog.bald.arcade.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private class EncryptedSessionManager(context: Context) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences by lazy {
        try {
            createPrefs(context)
        } catch (_: Exception) {
            try {
                val prefFile = File(context.filesDir.parentFile, "shared_prefs/balddog_secure_session.xml")
                if (prefFile.exists()) prefFile.delete()
                createPrefs(context)
            } catch (_: Exception) {
                context.getSharedPreferences("balddog_secure_session_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "balddog_secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString("session_json", json.encodeToString(session)).apply()
    }

    override suspend fun loadSession(): UserSession? {
        val raw = prefs.getString("session_json", null) ?: return null
        return runCatching { json.decodeFromString<UserSession>(raw) }.getOrNull()
    }

    override suspend fun deleteSession() {
        prefs.edit().remove("session_json").apply()
    }
}

object SupabaseClientProvider {
    @Volatile
    private var instance: SupabaseClient? = null

    fun get(context: Context): SupabaseClient {
        return instance ?: synchronized(this) {
            instance ?: createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_ANON_KEY
            ) {
                install(Auth) {
                    scheme = "dog.bald.arcade"
                    host = "login-callback"
                    sessionManager = EncryptedSessionManager(context.applicationContext)
                }
                install(Postgrest)
                install(Realtime)
            }.also { instance = it }
        }
    }
}
