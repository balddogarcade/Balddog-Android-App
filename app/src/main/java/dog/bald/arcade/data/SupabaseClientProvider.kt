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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private class EncryptedSessionManager(context: Context) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
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
                    sessionManager = EncryptedSessionManager(context.applicationContext)
                }
                install(Postgrest)
                install(Realtime)
            }.also { instance = it }
        }
    }
}
