package dog.bald.arcade.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface ProfileCacheStore {
    fun read(userId: String): String?

    fun write(
        userId: String,
        payload: String,
    )

    fun clear()
}

private class SharedPrefsProfileCacheStore(
    context: Context,
) : ProfileCacheStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(userId: String): String? = prefs.getString(keyFor(userId), null)

    override fun write(
        userId: String,
        payload: String,
    ) {
        prefs.edit().putString(keyFor(userId), payload).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(userId: String): String = "profile_$userId"

    companion object {
        private const val PREFS_NAME = "balddog_profile_cache"
    }
}

internal class ProfileCacheEngine(
    private val store: ProfileCacheStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(userId: String?): ProfileDto? {
        val cleanId = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val raw = store.read(cleanId) ?: return null
        return runCatching { json.decodeFromString<ProfileDto>(raw) }.getOrNull()
    }

    fun save(
        userId: String?,
        profile: ProfileDto,
    ) {
        val cleanId = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching { json.encodeToString(profile) }.getOrNull()?.let { encoded ->
            store.write(cleanId, encoded)
        }
    }

    fun clear() {
        store.clear()
    }
}

object ProfileCache {
    @Volatile
    private var engine: ProfileCacheEngine? = null

    private fun engineFor(context: Context): ProfileCacheEngine =
        engine ?: synchronized(this) {
            engine ?: ProfileCacheEngine(SharedPrefsProfileCacheStore(context)).also { engine = it }
        }

    fun load(
        context: Context,
        userId: String?,
    ): ProfileDto? = engineFor(context).load(userId)

    fun save(
        context: Context,
        userId: String?,
        profile: ProfileDto,
    ) {
        engineFor(context).save(userId, profile)
    }

    fun clear(context: Context) {
        engineFor(context).clear()
    }
}
