package dog.bald.arcade.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarRandomizerTest {

    @Test
    fun computeAvatarSignatureIsDeterministic() {
        val seed = "test_user_seed_12345"
        val sig1 = computeAvatarSignature(seed)
        val sig2 = computeAvatarSignature(seed)

        assertEquals(sig1.skinColor, sig2.skinColor)
        assertEquals(sig1.hairKey, sig2.hairKey)
        assertEquals(sig1.hairColor, sig2.hairColor)
        assertEquals(sig1.clothingKey, sig2.clothingKey)
        assertEquals(sig1.clothingColor, sig2.clothingColor)
        assertEquals(sig1.eyesKey, sig2.eyesKey)
        assertEquals(sig1.mouthKey, sig2.mouthKey)
        assertEquals(sig1.showGlasses, sig2.showGlasses)
        assertEquals(sig1.showHat, sig2.showHat)
        assertEquals(sig1.showBeard, sig2.showBeard)
    }

    @Test
    fun randomAvatarSeedGeneratesValidSeedWhenCurrentIsNull() {
        val seed = randomAvatarSeed(null)
        assertNotNull(seed)
        assertEquals(24, seed.length)
        assertTrue(seed.all { it.isLetterOrDigit() })
    }

    @Test
    fun randomAvatarSeedProducesVisuallyDistinctAvatar() {
        var currentSeed = "initial_seed_player_1"
        repeat(20) {
            val currentSig = computeAvatarSignature(currentSeed)
            val newSeed = randomAvatarSeed(currentSeed)

            assertNotEquals("Seed must not be identical", currentSeed, newSeed)
            val newSig = computeAvatarSignature(newSeed)

            // Calculate differences across major visual traits
            val diffSkin = currentSig.skinColor != newSig.skinColor
            val diffHair = currentSig.hairKey != newSig.hairKey
            val diffHairColor = currentSig.hairColor != newSig.hairColor
            val diffClothing = currentSig.clothingKey != newSig.clothingKey
            val diffClothingColor = currentSig.clothingColor != newSig.clothingColor

            var diffCount = 0
            if (diffSkin) diffCount++
            if (diffHair) diffCount++
            if (diffHairColor) diffCount++
            if (diffClothing) diffCount++
            if (diffClothingColor) diffCount++

            // The randomizer must guarantee significant visual difference (at least 4 out of 5 major traits differ)
            assertTrue(
                "Expected at least 4 major trait differences between consecutive avatars, got $diffCount",
                diffCount >= 4
            )

            currentSeed = newSeed
        }
    }
}
