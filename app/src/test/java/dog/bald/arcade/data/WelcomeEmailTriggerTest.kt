package dog.bald.arcade.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeEmailTriggerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `shouldTriggerWelcomeEmail returns true when welcomeEmailSent is false`() {
        val profile = ProfileDto(id = "u1", username = "player1", welcomeEmailSent = false)
        assertTrue(ArcadeRepository.shouldTriggerWelcomeEmail(profile))
    }

    @Test
    fun `shouldTriggerWelcomeEmail returns false when welcomeEmailSent is true`() {
        val profile = ProfileDto(id = "u1", username = "player1", welcomeEmailSent = true)
        assertFalse(ArcadeRepository.shouldTriggerWelcomeEmail(profile))
    }

    @Test
    fun `shouldTriggerWelcomeEmail returns false when profile is null`() {
        assertFalse(ArcadeRepository.shouldTriggerWelcomeEmail(null))
    }

    @Test
    fun `ProfileDto deserializes welcome_email_sent with default false`() {
        val withoutField = json.decodeFromString<ProfileDto>("""{"id":"u1","username":"p1"}""")
        assertFalse(withoutField.welcomeEmailSent)

        val withTrue = json.decodeFromString<ProfileDto>(
            """{"id":"u1","username":"p1","welcome_email_sent":true}"""
        )
        assertTrue(withTrue.welcomeEmailSent)
    }
}
