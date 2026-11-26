package dog.bald.arcade.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class InMemoryProfileCacheStore : ProfileCacheStore {
    private val map = mutableMapOf<String, String>()

    override fun read(userId: String): String? = map[userId]

    override fun write(userId: String, payload: String) {
        map[userId] = payload
    }

    override fun clear() {
        map.clear()
    }
}

class ProfileCacheTest {

    @Test
    fun saveAndLoadReturnsCachedProfileForSameUser() {
        val engine = ProfileCacheEngine(InMemoryProfileCacheStore())
        val profile = ProfileDto(
            id = "user-123",
            username = "bald_player",
            avatarSeed = "av_12345678",
            countryCode = "AR",
            totalPlaySeconds = 420L
        )

        engine.save("user-123", profile)
        val loaded = engine.load("user-123")

        assertEquals(profile, loaded)
        assertNull(engine.load("other-user"))
    }

    @Test
    fun clearRemovesAllCachedProfiles() {
        val engine = ProfileCacheEngine(InMemoryProfileCacheStore())
        engine.save("user-123", ProfileDto(id = "user-123", username = "bald_player"))

        engine.clear()

        assertNull(engine.load("user-123"))
    }

    @Test
    fun blankUserIdIsIgnored() {
        val engine = ProfileCacheEngine(InMemoryProfileCacheStore())
        engine.save("   ", ProfileDto(id = "user-123", username = "bald_player"))

        assertNull(engine.load("   "))
        assertNull(engine.load(null))
    }
}
