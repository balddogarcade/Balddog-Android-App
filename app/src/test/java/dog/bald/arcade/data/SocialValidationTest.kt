package dog.bald.arcade.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialValidationTest {

    @Test
    fun testUsernameValidation() {
        assertNull(SocialValidation.validateUsername("player_one"))
        assertNull(SocialValidation.validateUsername("abc"))
        assertNull(SocialValidation.validateUsername("12345678901234567890")) // 20 chars

        assertNotNull(SocialValidation.validateUsername("ab")) // too short
        assertNotNull(SocialValidation.validateUsername("123456789012345678901")) // 21 chars
        assertNotNull(SocialValidation.validateUsername("player-one")) // dash not allowed
        assertNotNull(SocialValidation.validateUsername("player.one")) // dot not allowed
        assertNotNull(SocialValidation.validateUsername("player one")) // space not allowed
    }

    @Test
    fun testSocialLinksValidation() {
        val valid = SocialLinksDto(
            x = "https://x.com/balddog",
            youtube = "https://youtube.com/@balddog"
        )
        val validErrors = SocialValidation.validateSocialLinks(valid)
        assertTrue(validErrors.isEmpty())

        val invalidHttp = SocialLinksDto(
            x = "http://x.com/balddog",
            discord = "http://discord.gg/balddog"
        )
        val httpErrors = SocialValidation.validateSocialLinks(invalidHttp)
        assertEquals("Debe iniciar con https://", httpErrors["x"])
        assertEquals("Usa https:// o tu usuario/tag de Discord", httpErrors["discord"])

        val invalidDomain = SocialLinksDto(
            x = "https://fake-site.com/user"
        )
        val domainErrors = SocialValidation.validateSocialLinks(invalidDomain)
        assertNotNull(domainErrors["x"])
    }
}
