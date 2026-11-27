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

    @Test
    fun parseDiceBearSvgLayersResolvesDefsAndUseTransforms() {
        val svg =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="none">
              <defs>
                <g id="clothes-variant21">
                  <path d="M3 1H2v3h3V3H4V2H3z" fill="#ffc425"/>
                </g>
                <g id="eyes-variant09">
                  <path fill="#ffffff" d="M0 0h3v3H0z"/>
                  <path fill="#697b94" d="M6 1h1v1H6z"/>
                </g>
                <g id="mouth-happy05">
                  <path d="M1 0v2h2V1H2V0z" fill="#de0f0d"/>
                </g>
              </defs>
              <g clip-path="url(#clip-1)">
                <path d="M4 2h8v1h1v3h1v2h-1v3h-1v1H9v1h4v1h1v2H2v-2h1v-1h4v-1H4v-1H3V8H2V6h1V3h1z" fill="#ffdbac"/>
                <use transform="translate(2 12)" href="#clothes-variant21"/>
                <use transform="translate(4 5)" href="#eyes-variant09"/>
                <use transform="translate(6 9)" href="#mouth-happy05"/>
              </g>
            </svg>
            """.trimIndent()

        val layers = parseDiceBearSvgLayers(svg)
        assertEquals(5, layers.size)

        // Base head
        assertEquals(0f, layers[0].offsetX, 0.001f)
        assertEquals(0f, layers[0].offsetY, 0.001f)

        // Clothes translated to (2, 12)
        assertEquals(2f, layers[1].offsetX, 0.001f)
        assertEquals(12f, layers[1].offsetY, 0.001f)

        // Eyes (2 paths) translated to (4, 5)
        assertEquals(4f, layers[2].offsetX, 0.001f)
        assertEquals(5f, layers[2].offsetY, 0.001f)
        assertEquals(4f, layers[3].offsetX, 0.001f)
        assertEquals(5f, layers[3].offsetY, 0.001f)

        // Mouth translated to (6, 9)
        assertEquals(6f, layers[4].offsetX, 0.001f)
        assertEquals(9f, layers[4].offsetY, 0.001f)
    }
}
