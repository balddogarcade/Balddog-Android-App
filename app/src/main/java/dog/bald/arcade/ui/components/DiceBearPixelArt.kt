package dog.bald.arcade.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import java.security.SecureRandom
import kotlin.math.floor

/**
 * Offline implementation of DiceBear Pixel Art (v9.4.2, CC0 / MIT)
 * Generates deterministic 16x16 pixel-art avatars from a seed (e.g. user email)
 * with zero network requests.
 */
@Composable
fun DiceBearPixelArtAvatar(
    seed: String,
    modifier: Modifier = Modifier,
) {
    val layers = remember(seed) { buildPixelArtLayers(seed) }
    Canvas(modifier = modifier) {
        scale(
            scaleX = size.width / 16f,
            scaleY = size.height / 16f,
            pivot = Offset.Zero,
        ) {
            for (layer in layers) {
                drawPath(
                    path = layer.path,
                    color = layer.color,
                    alpha = layer.alpha,
                )
            }
        }
    }
}

/**
 * Visual signature representing the rendered traits of an avatar from a given seed.
 */
data class AvatarVisualSignature(
    val skinColor: Color,
    val hairKey: String,
    val hairColor: Color,
    val clothingKey: String,
    val clothingColor: Color,
    val eyesKey: String,
    val mouthKey: String,
    val showGlasses: Boolean,
    val glassesKey: String?,
    val showHat: Boolean,
    val hatKey: String?,
    val showBeard: Boolean,
    val beardKey: String?,
    val showAccessories: Boolean,
    val accessoriesKey: String?,
)

/**
 * Derives the visual signature of an avatar generated from [seed].
 */
fun computeAvatarSignature(seed: String): AvatarVisualSignature {
    val prng = DiceBearPrng(seed)
    val accessoriesKey = prng.pick(ACCESSORIES_KEYS, "")
    val clothingKey = prng.pick(CLOTHING_KEYS, "")
    val eyesKey = prng.pick(EYES_KEYS, "")
    val glassesKey = prng.pick(GLASSES_KEYS, "")
    val beardKey = prng.pick(BEARD_KEYS, "")
    val mouthKey = prng.pick(MOUTH_KEYS, "")
    val hairKey = prng.pick(HAIR_KEYS, "")
    val hatKey = prng.pick(HAT_KEYS, "")

    val showAccessories = prng.bool(15)
    val showGlasses = prng.bool(20)
    val showBeard = prng.bool(15)
    val showHat = prng.bool(15)

    val colors =
        AvatarColors(
            accessories = hexToColor(prng.pick(ACCESSORIES_COLORS, "transparent")),
            clothing = hexToColor(prng.pick(CLOTHING_COLORS, "transparent")),
            eyes = hexToColor(prng.pick(EYES_COLORS, "transparent")),
            glasses = hexToColor(prng.pick(GLASSES_COLORS, "transparent")),
            hair = hexToColor(prng.pick(HAIR_COLORS, "transparent")),
            hat = hexToColor(prng.pick(HAT_COLORS, "transparent")),
            mouth = hexToColor(prng.pick(MOUTH_COLORS, "transparent")),
            skin = hexToColor(prng.pick(SKIN_COLORS, "transparent")),
        )

    return AvatarVisualSignature(
        skinColor = colors.skin,
        hairKey = hairKey,
        hairColor = colors.hair,
        clothingKey = clothingKey,
        clothingColor = colors.clothing,
        eyesKey = eyesKey,
        mouthKey = mouthKey,
        showGlasses = showGlasses,
        glassesKey = if (showGlasses) glassesKey else null,
        showHat = showHat,
        hatKey = if (showHat) hatKey else null,
        showBeard = showBeard,
        beardKey = if (showBeard) beardKey else null,
        showAccessories = showAccessories,
        accessoriesKey = if (showAccessories) accessoriesKey else null,
    )
}

private val avatarSeedRandom = SecureRandom()
private const val SEED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private fun generateRawSeed(): String =
    buildString(24) {
        repeat(24) {
            append(SEED_CHARS[avatarSeedRandom.nextInt(SEED_CHARS.length)])
        }
    }

/**
 * Generates a new random avatar seed guaranteed to produce an avatar visually
 * distinct from [currentSeed] across key traits (skin, hairstyle, hair color, clothing style, and clothing color).
 */
fun randomAvatarSeed(currentSeed: String? = null): String {
    if (currentSeed.isNullOrBlank()) {
        return generateRawSeed()
    }

    val currentSig = computeAvatarSignature(currentSeed)
    var bestSeed = generateRawSeed()
    var bestScore = -1

    for (attempt in 0 until 100) {
        val candidate = generateRawSeed()
        if (candidate == currentSeed) continue
        val candSig = computeAvatarSignature(candidate)

        val diffSkin = candSig.skinColor != currentSig.skinColor
        val diffHair = candSig.hairKey != currentSig.hairKey
        val diffHairColor = candSig.hairColor != currentSig.hairColor
        val diffClothing = candSig.clothingKey != currentSig.clothingKey
        val diffClothingColor = candSig.clothingColor != currentSig.clothingColor
        val diffMouth = candSig.mouthKey != currentSig.mouthKey
        val diffEyes = candSig.eyesKey != currentSig.eyesKey

        // Major visual traits: all 5 must differ for immediate acceptance
        if (diffSkin && diffHair && diffHairColor && diffClothing && diffClothingColor) {
            return candidate
        }

        var score = 0
        if (diffSkin) score += 3
        if (diffHair) score += 3
        if (diffHairColor) score += 3
        if (diffClothing) score += 2
        if (diffClothingColor) score += 3
        if (diffMouth) score += 1
        if (diffEyes) score += 1
        if (candSig.showGlasses != currentSig.showGlasses || candSig.glassesKey != currentSig.glassesKey) score += 1
        if (candSig.showHat != currentSig.showHat || candSig.hatKey != currentSig.hatKey) score += 1
        if (candSig.showBeard != currentSig.showBeard || candSig.beardKey != currentSig.beardKey) score += 1

        if (score > bestScore) {
            bestScore = score
            bestSeed = candidate
        }
    }

    return bestSeed
}

private data class ResolvedLayer(
    val path: Path,
    val color: Color,
    val alpha: Float,
)

private sealed interface FillRef {
    data object SKIN : FillRef

    data object HAIR : FillRef

    data object CLOTHING : FillRef

    data object EYES : FillRef

    data object MOUTH : FillRef

    data object GLASSES : FillRef

    data object ACCESSORIES : FillRef

    data object HAT : FillRef

    data object WHITE : FillRef

    data object BLACK : FillRef

    data class HEX(
        val argb: Long,
    ) : FillRef
}

private data class PathSpec(
    val d: String,
    val fill: FillRef,
    val alpha: Float = 1f,
    val evenOdd: Boolean = false,
)

private data class AvatarColors(
    val accessories: Color,
    val clothing: Color,
    val eyes: Color,
    val glasses: Color,
    val hair: Color,
    val hat: Color,
    val mouth: Color,
    val skin: Color,
)

private class DiceBearPrng(
    seed: String,
) {
    private var value: Int

    init {
        val trimmed = seed.take(1024)
        var hash = 0
        for (ch in trimmed) {
            hash = ((hash shl 5) - hash + ch.code)
            hash = xorshift(hash)
        }
        value = if (hash == 0) 1 else hash
    }

    private fun xorshift(v: Int): Int {
        var r = v
        r = r xor (r shl 13)
        r = r xor (r shr 17)
        r = r xor (r shl 5)
        return r
    }

    fun next(): Int {
        value = xorshift(value)
        return value
    }

    fun integer(
        min: Int,
        max: Int,
    ): Int {
        val ratio = (next().toDouble() - (-2147483648.0)) / (2147483647.0 - (-2147483648.0))
        return floor(ratio * (max + 1 - min) + min).toInt().coerceIn(min, max)
    }

    fun bool(likelihood: Int = 50): Boolean = integer(1, 100) <= likelihood

    fun <T> pick(
        items: List<T>,
        fallback: T,
    ): T {
        if (items.isEmpty()) {
            next()
            return fallback
        }
        return items[integer(0, items.size - 1)]
    }
}

private fun hexToColor(hex: String): Color {
    if (hex == "transparent") return Color.Transparent
    return Color(("FF" + hex).toLong(16))
}

private fun resolveFill(
    ref: FillRef,
    colors: AvatarColors,
): Color =
    when (ref) {
        FillRef.SKIN -> colors.skin
        FillRef.HAIR -> colors.hair
        FillRef.CLOTHING -> colors.clothing
        FillRef.EYES -> colors.eyes
        FillRef.MOUTH -> colors.mouth
        FillRef.GLASSES -> colors.glasses
        FillRef.ACCESSORIES -> colors.accessories
        FillRef.HAT -> colors.hat
        FillRef.WHITE -> Color.White
        FillRef.BLACK -> Color.Black
        is FillRef.HEX -> Color(ref.argb)
    }

private fun buildPixelArtLayers(seed: String): List<ResolvedLayer> {
    val prng = DiceBearPrng(seed)

    // Pick components in exact DiceBear pixel-art order
    val accessoriesKey = prng.pick(ACCESSORIES_KEYS, "")
    val clothingKey = prng.pick(CLOTHING_KEYS, "")
    val eyesKey = prng.pick(EYES_KEYS, "")
    val glassesKey = prng.pick(GLASSES_KEYS, "")
    val beardKey = prng.pick(BEARD_KEYS, "")
    val mouthKey = prng.pick(MOUTH_KEYS, "")
    val hairKey = prng.pick(HAIR_KEYS, "")
    val hatKey = prng.pick(HAT_KEYS, "")

    val showAccessories = prng.bool(15)
    val showGlasses = prng.bool(20)
    val showBeard = prng.bool(15)
    val showHat = prng.bool(15)

    // Pick colors in exact DiceBear pixel-art order
    val colors =
        AvatarColors(
            accessories = hexToColor(prng.pick(ACCESSORIES_COLORS, "transparent")),
            clothing = hexToColor(prng.pick(CLOTHING_COLORS, "transparent")),
            eyes = hexToColor(prng.pick(EYES_COLORS, "transparent")),
            glasses = hexToColor(prng.pick(GLASSES_COLORS, "transparent")),
            hair = hexToColor(prng.pick(HAIR_COLORS, "transparent")),
            hat = hexToColor(prng.pick(HAT_COLORS, "transparent")),
            mouth = hexToColor(prng.pick(MOUTH_COLORS, "transparent")),
            skin = hexToColor(prng.pick(SKIN_COLORS, "transparent")),
        )

    val specs = mutableListOf<PathSpec>()
    // Base head & neck
    specs += PathSpec("M4 2h8v1h1v3h1v2h-1v3h-1v1H9v1h4v1h1v2H2v-2h1v-1h4v-1H4v-1H3V8H2V6h1V3h1V2Z", FillRef.SKIN)
    specs += PathSpec("M4 2h8v1h1v3h1v2h-1v3h-1v1H4v-1H3V8H2V6h1V3h1V2Z", FillRef.WHITE, alpha = 0.1f)

    if (showAccessories) accessoriesMap[accessoriesKey]?.let { specs += it }
    clothingMap[clothingKey]?.let { specs += it }
    eyesMap[eyesKey]?.let { specs += it }
    if (showGlasses) glassesMap[glassesKey]?.let { specs += it }
    if (showBeard) beardMap[beardKey]?.let { specs += it }
    mouthMap[mouthKey]?.let { specs += it }
    hairMap[hairKey]?.let { specs += it }
    if (showHat) hatMap[hatKey]?.let { specs += it }

    return specs.map { spec ->
        val path =
            PathParser().parsePathString(spec.d).toPath().apply {
                if (spec.evenOdd) fillType = PathFillType.EvenOdd
            }
        ResolvedLayer(
            path = path,
            color = resolveFill(spec.fill, colors),
            alpha = spec.alpha,
        )
    }
}

private val ACCESSORIES_KEYS = listOf("variant04", "variant03", "variant02", "variant01")
private val ACCESSORIES_COLORS = listOf("daa520", "ffd700", "fafad2", "d3d3d3", "a9a9a9")
private val BEARD_KEYS = listOf("variant08", "variant07", "variant06", "variant05", "variant04", "variant03", "variant02", "variant01")
private val CLOTHING_KEYS =
    listOf(
        "variant23",
        "variant22",
        "variant21",
        "variant20",
        "variant19",
        "variant18",
        "variant17",
        "variant16",
        "variant15",
        "variant14",
        "variant13",
        "variant12",
        "variant11",
        "variant10",
        "variant09",
        "variant08",
        "variant07",
        "variant06",
        "variant05",
        "variant04",
        "variant03",
        "variant02",
        "variant01",
    )
private val CLOTHING_COLORS =
    listOf(
        "5bc0de",
        "428bca",
        "03396c",
        "88d8b0",
        "44c585",
        "00b159",
        "ff6f69",
        "d11141",
        "ae0001",
        "ffeead",
        "ffd969",
        "ffc425",
    )
private val EYES_KEYS =
    listOf(
        "variant12",
        "variant11",
        "variant10",
        "variant09",
        "variant08",
        "variant07",
        "variant06",
        "variant05",
        "variant04",
        "variant03",
        "variant02",
        "variant01",
    )
private val EYES_COLORS = listOf("76778b", "697b94", "647b90", "5b7c8b", "588387", "876658")
private val GLASSES_KEYS =
    listOf(
        "light07",
        "light06",
        "light05",
        "light04",
        "light03",
        "light02",
        "light01",
        "dark07",
        "dark06",
        "dark05",
        "dark04",
        "dark03",
        "dark02",
        "dark01",
    )
private val GLASSES_COLORS = listOf("4b4b4b", "323232", "191919", "43677d", "5f705c", "a04b5d")
private val HAIR_KEYS =
    listOf(
        "short24",
        "short23",
        "short22",
        "short21",
        "short20",
        "short19",
        "short18",
        "short17",
        "short16",
        "short15",
        "short14",
        "short13",
        "short12",
        "short11",
        "short10",
        "short09",
        "short08",
        "short07",
        "short06",
        "short05",
        "short04",
        "short03",
        "short02",
        "short01",
        "long21",
        "long20",
        "long19",
        "long18",
        "long17",
        "long16",
        "long15",
        "long14",
        "long13",
        "long12",
        "long11",
        "long10",
        "long09",
        "long08",
        "long07",
        "long06",
        "long05",
        "long04",
        "long03",
        "long02",
        "long01",
    )
private val HAIR_COLORS =
    listOf(
        "cab188",
        "603a14",
        "83623b",
        "a78961",
        "611c17",
        "603015",
        "612616",
        "28150a",
        "009bbd",
        "bd1700",
        "91cb15",
    )
private val HAT_KEYS =
    listOf(
        "variant10",
        "variant09",
        "variant08",
        "variant07",
        "variant06",
        "variant05",
        "variant04",
        "variant03",
        "variant02",
        "variant01",
    )
private val HAT_COLORS = listOf("2e1e05", "2663a3", "989789", "3d8a6b", "cc6192", "614f8a", "a62116")
private val MOUTH_KEYS =
    listOf(
        "sad10",
        "sad09",
        "sad08",
        "sad07",
        "sad06",
        "sad05",
        "sad04",
        "sad03",
        "sad02",
        "sad01",
        "happy13",
        "happy12",
        "happy11",
        "happy10",
        "happy09",
        "happy08",
        "happy07",
        "happy06",
        "happy05",
        "happy04",
        "happy03",
        "happy02",
        "happy01",
    )
private val MOUTH_COLORS = listOf("d29985", "c98276", "e35d6a", "de0f0d")
private val SKIN_COLORS = listOf("ffdbac", "f5cfa0", "eac393", "e0b687", "cb9e6e", "b68655", "a26d3d", "8d5524")

private val accessoriesMap: Map<String, List<PathSpec>> =
    mapOf(
        "variant04" to
            listOf(
                PathSpec("M2 7h1v1H2zM13 7h1v1h-1z", FillRef.ACCESSORIES),
            ),
        "variant03" to
            listOf(
                PathSpec("M13 7h1v2h-1zM2 7h1v2H2z", FillRef.ACCESSORIES),
            ),
        "variant02" to
            listOf(
                PathSpec("M13 7h1v2h-1zM2 7h1v2H2z", FillRef.ACCESSORIES),
                PathSpec("M2 7h1v1H2zM13 7h1v1h-1z", FillRef.WHITE, alpha = 0.5f),
            ),
        "variant01" to
            listOf(
                PathSpec("M13 7h2v2h-2zM1 7h2v2H1z", FillRef.ACCESSORIES),
            ),
    )

private val beardMap: Map<String, List<PathSpec>> =
    mapOf(
        "variant08" to
            listOf(
                PathSpec("M3 6h1v3h8V6h1v5h-1v1H4v-1H3V6Z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M3 6h1v3h8V6h1v5h-1v1H4v-1H3V6Z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant07" to
            listOf(
                PathSpec("M3 9v2h1v1h8v-1h1V9H3Z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M3 9v2h1v1h8v-1h1V9H3Z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant06" to
            listOf(
                PathSpec("M3 9h3v1h4V9h3v2h-1v1H4v-1H3V9Z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M3 9h3v1h4V9h3v2h-1v1H4v-1H3V9Z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant05" to
            listOf(
                PathSpec("M3 6h1v2h1v1h6V8h1V6h1v5h-1v1H4v-1H3V6Z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M3 6h1v2h1v1h6V8h1V6h1v5h-1v1H4v-1H3V6Z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant04" to
            listOf(
                PathSpec("M6 11h4v1H6z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M6 11h4v1H6z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant03" to
            listOf(
                PathSpec("M3 9h2v1h6V9h2v2h-1v2h-1v1H5v-1H4v-2H3V9Z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M3 9h2v1h6V9h2v2h-1v2h-1v1H5v-1H4v-2H3V9Z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant02" to
            listOf(
                PathSpec("M5 11h6v1h-1v1H9v1H7v-1H6v-1H5v-1Z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M5 11h6v1h-1v1H9v1H7v-1H6v-1H5v-1Z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant01" to
            listOf(
                PathSpec("M3 8h2v1h1v1h4V9h1V8h2v3h-1v1H4v-1H3V8Z", FillRef.HAIR, alpha = 0.9f),
                PathSpec("M3 8h2v1h1v1h4V9h1V8h2v3h-1v1H4v-1H3V8Z", FillRef.WHITE, alpha = 0.3f),
            ),
    )

private val clothingMap: Map<String, List<PathSpec>> =
    mapOf(
        "variant23" to
            listOf(
                PathSpec("M10 13h3v1h1v2H2v-2h1v-1h3v1h4v-1Z", FillRef.CLOTHING),
                PathSpec("M4 13h1v3H4zM11 13h1v3h-1z", FillRef.WHITE, alpha = 0.2f),
            ),
        "variant22" to
            listOf(
                PathSpec("M6 13H4v3h2v-3ZM12 13h-2v3h2v-3Z", FillRef.CLOTHING),
                PathSpec("M5 13h1v3H5zM10 13h1v3h-1z", FillRef.WHITE, alpha = 0.2f),
            ),
        "variant21" to
            listOf(
                PathSpec("M5 13H4v3h3v-1H6v-1H5v-1ZM12 13h-1v1h-1v1H9v1h3v-3Z", FillRef.CLOTHING),
                PathSpec("M4 13h1v1H4zM5 14h1v1H5zM6 15h1v1H6zM9 15h1v1H9zM10 14h1v1h-1z", FillRef.WHITE, alpha = 0.5f),
                PathSpec("M11 14h1v1h-1z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M11 13h1v1h-1z", FillRef.WHITE, alpha = 0.5f),
                PathSpec("M10 15h1v1h-1zM5 15h1v1H5zM4 14h1v1H4z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant20" to
            listOf(
                PathSpec("M4 14h8v2H4z", FillRef.CLOTHING),
                PathSpec("M4 14h1v2H4zM11 14h1v2h-1z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant19" to
            listOf(
                PathSpec("M4 13h1v1h6v-1h1v3H4v-3Z", FillRef.CLOTHING),
                PathSpec("M9 15h2v1H9z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant18" to
            listOf(
                PathSpec("M4 13h2v1h1v1h2v-1h1v-1h2v3H4v-3Z", FillRef.CLOTHING),
                PathSpec("M10 14h1v1h-1zM11 13h1v1h-1zM9 15h1v1H9z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M9 14h1v1H9zM10 13h1v1h-1zM8 15h1v1H8z", FillRef.WHITE, alpha = 0.5f),
                PathSpec("M6 14H5v1h1zM5 13H4v1h1zM7 15H6v1h1z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M7 14H6v1h1zM6 13H5v1h1zM8 15H7v1h1z", FillRef.WHITE, alpha = 0.5f),
            ),
        "variant17" to
            listOf(
                PathSpec("M4 13h2v1h1v1h2v-1h1v-1h2v1h1v2H3v-2h1v-1Z", FillRef.CLOTHING),
            ),
        "variant16" to
            listOf(
                PathSpec("M5 13H3v1H2v2h5v-1H6v-1H5v-1ZM13 13h-2v1h-1v1H9v1h5v-2h-1v-1Z", FillRef.CLOTHING),
                PathSpec("M11 13h1v1h-1zM10 14h1v1h-1zM9 15h1v1H9zM6 15h1v1H6zM5 14h1v1H5zM4 13h1v1H4z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant15" to
            listOf(
                PathSpec("M6 14H4v1H3v1h4v-1H6v-1ZM12 15h1v1H9v-1h1v-1h2v1Z", FillRef.CLOTHING),
            ),
        "variant14" to
            listOf(
                PathSpec("M5 13H3v1H2v2h12v-2h-1v-1h-2v1h-1v1H6v-1H5v-1Z", FillRef.CLOTHING),
            ),
        "variant13" to
            listOf(
                PathSpec("M5 13H3v1H2v2h12v-2h-1v-1h-2v1H5v-1Z", FillRef.CLOTHING),
                PathSpec("M2 14h1v2H2zM6 14h1v2H6zM8 14h1v2H8zM10 14h1v2h-1zM4 13h1v3H4zM12 13h1v3h-1z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant12" to
            listOf(
                PathSpec("M7 12h2v1h4v1h1v2H2v-2h1v-1h4v-1Z", FillRef.CLOTHING),
                PathSpec("M7 12h2v1H7z", FillRef.BLACK, alpha = 0.3f),
            ),
        "variant11" to
            listOf(
                PathSpec("M10 13h3v1h1v2H2v-2h1v-1h3v1h1v1h2v-1h1v-1Z", FillRef.CLOTHING),
            ),
        "variant10" to
            listOf(
                PathSpec("M4 13h1v1h6v-1h1v1h1v2H3v-2h1v-1Z", FillRef.CLOTHING),
                PathSpec("M9 15h2v1H9z", FillRef.WHITE, alpha = 0.5f),
            ),
        "variant09" to
            listOf(
                PathSpec("M4 13h1v2h6v-2h1v2h1v1H3v-1h1v-2Z", FillRef.CLOTHING),
                PathSpec("M4 13h1v2H4zM11 13h1v2h-1z", FillRef.WHITE, alpha = 0.2f),
            ),
        "variant08" to
            listOf(
                PathSpec("M10 13h3v1h1v2H2v-2h1v-1h3v2h4v-2Z", FillRef.CLOTHING),
            ),
        "variant07" to
            listOf(
                PathSpec("M5 13H4v3h3v-1H6v-1H5v-1ZM12 13h-1v1h-1v1H9v1h3v-3Z", FillRef.CLOTHING),
            ),
        "variant06" to
            listOf(
                PathSpec("M10 13h3v1h1v2H2v-2h1v-1h3v1h4v-1Z", FillRef.CLOTHING),
                PathSpec(
                    "M3 13h1v1H3zM2 14h1v1H2zM3 15h1v1H3zM4 14h1v1H4zM5 13h1v1H5zM5 15h1v1H5zM6 14h1v1H6zM7 15h1v1H7zM8 14h1v1H8zM9 15h1v1H9zM10 14h1v1h-1zM11 15h1v1h-1zM11 13h1v1h-1zM12 14h1v1h-1zM13 15h1v1h-1z",
                    FillRef.WHITE,
                    alpha = 0.2f,
                ),
            ),
        "variant05" to
            listOf(
                PathSpec("M5 13H3v1H2v2h12v-2h-1v-1h-2v1h-1v1H6v-1H5v-1Z", FillRef.CLOTHING),
            ),
        "variant04" to
            listOf(
                PathSpec("M5 13H3v1H2v2h12v-2h-1v-1h-2v1H5v-1Z", FillRef.CLOTHING),
            ),
        "variant03" to
            listOf(
                PathSpec("M10 13h3v1h1v2H2v-2h1v-1h3v1h4v-1Z", FillRef.CLOTHING),
            ),
        "variant02" to
            listOf(
                PathSpec("M10 13h3v1h1v2H2v-2h1v-1h3v2h4v-2Z", FillRef.CLOTHING),
                PathSpec(
                    "M5 14h1v1H5zM4 15h1v1H4zM7 15h1v1H7zM3 13h1v1H3zM2 14h1v1H2zM12 13h1v1h-1zM11 14h1v1h-1zM10 15h1v1h-1zM13 15h1v1h-1z",
                    FillRef.WHITE,
                    alpha = 0.2f,
                ),
            ),
        "variant01" to
            listOf(
                PathSpec("M10 13h3v1h1v2H2v-2h1v-1h3v1h4v-1Z", FillRef.CLOTHING),
                PathSpec("M11 14h1v2h-1zM4 14h1v2H4z", FillRef.WHITE, alpha = 0.2f),
            ),
    )

private val eyesMap: Map<String, List<PathSpec>> =
    mapOf(
        "variant12" to
            listOf(
                PathSpec("M5 6h1v2H4V7h1V6Zm5 0h1v2H9V7h1V6Z", FillRef.WHITE, evenOdd = true),
                PathSpec("M6 6v1H5v1h2V6H6Zm5 0v1h-1v1h2V6h-1Z", FillRef.EYES, evenOdd = true),
                PathSpec("M6 6v1h1V6H6ZM5 7v1h1V7H5Zm6-1v1h1V6h-1Zm-1 1v1h1V7h-1Z", FillRef.WHITE, alpha = 0.5f, evenOdd = true),
            ),
        "variant11" to
            listOf(
                PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE),
                PathSpec("M4 6h2v1H4zM9 6h2v1H9z", FillRef.EYES),
                PathSpec("M4 6h1v1H4zM9 6h1v1H9z", FillRef.WHITE, alpha = 0.7f),
            ),
        "variant10" to
            listOf(
                PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE),
                PathSpec("M10 5h2v2h-2zM5 5h2v2H5z", FillRef.EYES),
                PathSpec("M5 5h1v1H5zM6 6h1v1H6zM10 5h1v1h-1zM11 6h1v1h-1z", FillRef.WHITE, alpha = 0.4f),
                PathSpec("M11 5h1v1h-1zM6 5h1v1H6z", FillRef.WHITE, alpha = 0.7f),
            ),
        "variant09" to
            listOf(
                PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE),
                PathSpec("M10 6h1v1h-1zM5 6h1v1H5z", FillRef.EYES),
            ),
        "variant08" to
            listOf(
                PathSpec("M4 5h3v2H4zM9 5h3v2H9z", FillRef.WHITE),
                PathSpec("M9 6h1v1H9zM4 6h1v1H4z", FillRef.EYES),
            ),
        "variant07" to
            listOf(
                PathSpec("M4 5h3v2H4zM9 5h3v2H9z", FillRef.WHITE),
                PathSpec("M11 6h1v1h-1zM6 6h1v1H6z", FillRef.EYES),
            ),
        "variant06" to
            listOf(
                PathSpec("M4 5h3v2H4z", FillRef.WHITE),
                PathSpec("M5 6h1v1H5z", FillRef.EYES),
                PathSpec("M9 5h3v2H9z", FillRef.WHITE),
                PathSpec("M10 6h1v1h-1z", FillRef.EYES),
            ),
        "variant05" to
            listOf(
                PathSpec("M4 5h3v2H4zM9 5h3v2H9z", FillRef.WHITE),
                PathSpec("M11 5h-1v1H9v1h3V6h-1V5Z", FillRef.EYES),
                PathSpec("M9 6h1v1H9zM10 5h1v1h-1zM11 6h1v1h-1z", FillRef.WHITE, alpha = 0.5f),
                PathSpec("M6 5H5v1H4v1h3V6H6V5Z", FillRef.EYES),
                PathSpec("M4 6h1v1H4zM5 5h1v1H5zM6 6h1v1H6z", FillRef.WHITE, alpha = 0.5f),
            ),
        "variant04" to
            listOf(
                PathSpec("M6 5H5v2h2V6H6V5ZM11 5h-1v2h2V6h-1V5Z", FillRef.WHITE),
                PathSpec("M5 6h1v1H5zM10 6h1v1h-1z", FillRef.EYES),
            ),
        "variant03" to
            listOf(
                PathSpec("M10 5h1v2H9V6h1V5ZM5 5h1v2H4V6h1V5Z", FillRef.WHITE),
                PathSpec("M11 6h-1v1h1zM6 6H5v1h1z", FillRef.EYES),
            ),
        "variant02" to
            listOf(
                PathSpec("M11 5h-1v2h2V6h-1V5ZM6 5H5v2h2V6H6V5Z", FillRef.WHITE, evenOdd = true),
                PathSpec("M10 5v1h1v1H9V5h1ZM5 5v1h1v1H4V5h1Z", FillRef.EYES, evenOdd = true),
                PathSpec("M10 5v1H9V5h1Zm1 1v1h-1V6h1ZM5 5v1H4V5h1Zm1 1v1H5V6h1Z", FillRef.WHITE, alpha = 0.5f, evenOdd = true),
            ),
        "variant01" to
            listOf(
                PathSpec("M12 5H9v3h3zM7 5H4v3h3z", FillRef.WHITE),
                PathSpec("M12 6h-2v1h2zM7 6H5v1h2z", FillRef.EYES),
                PathSpec("M12 6h-1v1h1zM7 6H6v1h1z", FillRef.WHITE, alpha = 0.7f),
            ),
    )

private val glassesMap: Map<String, List<PathSpec>> =
    mapOf(
        "light07" to
            listOf(
                PathSpec(
                    "M12 4H9v1H7V4H4v1H2v1h1v2h1v1h3V8h2v1h3V8h1V6h1V5h-2V4Zm0 1v3H9V5h3ZM7 8H4V5h3v3Z",
                    FillRef.GLASSES,
                    evenOdd = true,
                ),
                PathSpec("M9 5h3v3H9zM4 5h3v3H4z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M2 5h1v1H2zM13 5h1v1h-1z", FillRef.WHITE, alpha = 0.2f),
            ),
        "light06" to
            listOf(
                PathSpec("M2 5v1h1v2h1v1h3V8h2v1h3V8h1V6h1V5H2Zm10 3H9V6h3v2ZM7 8H4V6h3v2Z", FillRef.GLASSES, evenOdd = true),
                PathSpec("M9 6h3v2H9zM4 6h3v2H4z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M7 5h2v1H7zM2 5h2v1H2zM12 5h2v1h-2z", FillRef.WHITE, alpha = 0.2f),
            ),
        "light05" to
            listOf(
                PathSpec("M2 5v1h1v2h1v1h3V8h2v1h3V8h1V6h1V5H9v1H7V5H2Zm5 1v2H4V6h3Zm2 0v2h3V6H9Z", FillRef.GLASSES, evenOdd = true),
                PathSpec("M4 6h3v2H4zM9 6h3v2H9z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M2 5h2v1H2zM12 5h2v1h-2z", FillRef.WHITE, alpha = 0.2f),
            ),
        "light04" to
            listOf(
                PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M3 5h1v1H3zM7 5h2v1H7zM12 5h1v1h-1z", FillRef.GLASSES),
            ),
        "light03" to
            listOf(
                PathSpec("M2 5h12v1H2zM3 7h10v1H3zM3 6h1v1H3zM12 6h1v1h-1z", FillRef.GLASSES),
                PathSpec("M4 6h8v1H4z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M2 5h1v1H2zM13 5h1v1h-1z", FillRef.WHITE, alpha = 0.2f),
                PathSpec("M7 6h2v1H7z", FillRef.BLACK, alpha = 0.3f),
            ),
        "light02" to
            listOf(
                PathSpec("M14 5H2v1h1v2h4V7h2v1h4V6h1V5Zm-2 1H9v1h3V6ZM7 7H4V6h3v1Z", FillRef.GLASSES, evenOdd = true),
                PathSpec("M4 6h3v1H4z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M2 5h1v1H2zM7 5h2v1H7zM13 5h1v1h-1z", FillRef.WHITE, alpha = 0.2f),
                PathSpec("M9 6h3v1H9z", FillRef.WHITE, alpha = 0.3f),
            ),
        "light01" to
            listOf(
                PathSpec("M2 5h5v1H4v1H3V6H2V5ZM7 7v1H4V7h3ZM9 7H7V6h2v1ZM12 7H9v1h3V7ZM12 6H9V5h5v1h-1v1h-1V6Z", FillRef.GLASSES),
                PathSpec("M4 6h3v1H4zM9 6h3v1H9z", FillRef.WHITE, alpha = 0.3f),
                PathSpec("M12 5h2v1h-2zM2 5h2v1H2zM7 6h2v1H7z", FillRef.WHITE, alpha = 0.2f),
            ),
        "dark07" to
            listOf(
                PathSpec(
                    "M12 4H9v1H7V4H4v1H2v1h1v2h1v1h3V8h2v1h3V8h1V6h1V5h-2V4Zm0 1v3H9V5h3ZM7 8H4V5h3v3Z",
                    FillRef.GLASSES,
                    evenOdd = true,
                ),
                PathSpec("M9 5h3v3H9zM4 5h3v3H4z", FillRef.BLACK, alpha = 0.3f),
                PathSpec("M2 5h1v1H2zM13 5h1v1h-1z", FillRef.WHITE, alpha = 0.2f),
            ),
        "dark06" to
            listOf(
                PathSpec("M2 5v1h1v2h1v1h3V8h2v1h3V8h1V6h1V5H2Zm10 3H9V6h3v2ZM7 8H4V6h3v2Z", FillRef.GLASSES, evenOdd = true),
                PathSpec("M9 6h3v2H9zM4 6h3v2H4z", FillRef.BLACK, alpha = 0.3f),
                PathSpec("M7 5h2v1H7zM2 5h2v1H2zM12 5h2v1h-2z", FillRef.WHITE, alpha = 0.2f),
            ),
        "dark05" to
            listOf(
                PathSpec("M2 5v1h1v2h1v1h3V8h2v1h3V8h1V6h1V5H9v1H7V5H2Zm5 1v2H4V6h3Zm2 0v2h3V6H9Z", FillRef.GLASSES, evenOdd = true),
                PathSpec("M4 6h3v2H4zM9 6h3v2H9z", FillRef.BLACK, alpha = 0.3f),
                PathSpec("M2 5h2v1H2zM12 5h2v1h-2z", FillRef.WHITE, alpha = 0.2f),
            ),
        "dark04" to
            listOf(
                PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.BLACK, alpha = 0.3f),
                PathSpec("M3 5h1v1H3zM7 5h2v1H7zM12 5h1v1h-1z", FillRef.GLASSES),
            ),
        "dark03" to
            listOf(
                PathSpec("M2 5h12v1H2zM3 7h10v1H3zM3 6h1v1H3zM12 6h1v1h-1z", FillRef.GLASSES),
                PathSpec("M4 6h8v1H4z", FillRef.BLACK, alpha = 0.3f),
                PathSpec("M2 5h1v1H2zM13 5h1v1h-1z", FillRef.WHITE, alpha = 0.2f),
                PathSpec("M7 6h2v1H7z", FillRef.BLACK, alpha = 0.3f),
            ),
        "dark02" to
            listOf(
                PathSpec("M14 5H2v1h1v2h4V7h2v1h4V6h1V5Zm-2 1H9v1h3V6ZM7 7H4V6h3v1Z", FillRef.GLASSES, evenOdd = true),
                PathSpec("M4 6h3v1H4z", FillRef.BLACK, alpha = 0.3f),
                PathSpec("M2 5h1v1H2zM7 5h2v1H7zM13 5h1v1h-1z", FillRef.WHITE, alpha = 0.2f),
                PathSpec("M9 6h3v1H9z", FillRef.BLACK, alpha = 0.3f),
            ),
        "dark01" to
            listOf(
                PathSpec("M2 5h5v1H4v1H3V6H2V5ZM7 7v1H4V7h3ZM9 7H7V6h2v1ZM12 7H9v1h3V7ZM12 6H9V5h5v1h-1v1h-1V6Z", FillRef.GLASSES),
                PathSpec("M4 6h3v1H4zM9 6h3v1H9z", FillRef.BLACK, alpha = 0.3f),
                PathSpec("M12 5h2v1h-2zM2 5h2v1H2zM7 6h2v1H7z", FillRef.WHITE, alpha = 0.2f),
            ),
    )

private val hairMap: Map<String, List<PathSpec>> =
    mapOf(
        "short24" to
            listOf(
                PathSpec("M11 2H5v1h1v1h4V3h1V2ZM3 3h1v2H3V3ZM13 3h-1v2h1V3Z", FillRef.HAIR),
            ),
        "short23" to
            listOf(
                PathSpec("M6 2h4v1H6z", FillRef.HAIR),
            ),
        "short22" to
            listOf(
                PathSpec("M4 2h8v1h1v2h-2V4H5v1H3V3h1V2Z", FillRef.HAIR),
            ),
        "short21" to
            listOf(
                PathSpec("M7 1h2v2H7z", FillRef.HAIR),
            ),
        "short20" to
            listOf(
                PathSpec("M6 2h4v1H6zM7 3h2v1H7z", FillRef.HAIR),
            ),
        "short19" to
            listOf(
                PathSpec("M4 2h8v1H4zM3 3h3v1H3zM10 3h3v1h-3zM8 3h1v1H8z", FillRef.HAIR),
                PathSpec("M8 3h1v1H8z", FillRef.HAIR),
            ),
        "short18" to
            listOf(
                PathSpec("M2 3h2v3H2zM12 3h2v3h-2z", FillRef.HAIR),
                PathSpec("M12 3h2v3h-2z", FillRef.HAIR),
            ),
        "short17" to
            listOf(
                PathSpec("M12 3h2v3h-2zM2 3h2v3H2z", FillRef.HAIR),
                PathSpec("M3 2h10v2H3z", FillRef.HAIR),
                PathSpec("M4 1h8v2H4z", FillRef.HAIR),
            ),
        "short16" to
            listOf(
                PathSpec("M3 2h10v2H3z", FillRef.HAIR),
                PathSpec("M4 1h8v2H4z", FillRef.HAIR),
            ),
        "short15" to
            listOf(
                PathSpec("M3 3h10v1H3zM4 2h8v1H4zM2 4h2v2H2zM12 4h2v2h-2z", FillRef.HAIR),
            ),
        "short14" to
            listOf(
                PathSpec("M3 4h1v3H3zM12 4h1v3h-1zM3 2h10v2H3z", FillRef.HAIR),
            ),
        "short13" to
            listOf(
                PathSpec("M3 2h10v2H3zM2 2h1v1H2zM2 1h1v1H2z", FillRef.HAIR),
                PathSpec(
                    "M2 2h1v1H2zM13 0h1v1h-1zM2 4h1v1H2zM5 0h1v1H5zM4 1h1v1H4zM6 1h1v1H6zM9 1h1v1H9zM8 0h1v1H8zM12 1h1v1h-1zM13 2h1v1h-1z",
                    FillRef.HAIR,
                ),
                PathSpec("M12 1h1v1h-1zM14 3h1v1h-1zM13 4h1v1h-1zM1 3h1v1H1z", FillRef.HAIR),
            ),
        "short12" to
            listOf(
                PathSpec("M4 2h1v3H4zM6 2h1v2H6zM8 2h1v2H8zM10 2h1v2h-1z", FillRef.HAIR),
            ),
        "short11" to
            listOf(
                PathSpec(
                    "M4 2h8v1H4zM3 1h1v1H3zM2 2h1v1H2zM2 3h1v1H2zM2 4h1v1H2zM2 5h1v1H2zM1 3h1v1H1zM1 1h1v1H1zM1 5h1v1H1zM1 6h1v1H1zM0 7h1v1H0zM1 8h1v1H1zM5 1h1v1H5zM6 0h1v1H6zM4 0h1v1H4zM6 1h1v1H6zM7 1h1v1H7zM8 0h1v1H8zM9 1h1v1H9zM9 0h1v1H9zM11 0h1v1h-1zM10 1h1v1h-1zM12 1h1v1h-1zM13 2h1v1h-1zM13 3h1v1h-1zM13 4h1v1h-1zM13 5h1v1h-1zM14 6h1v1h-1zM15 4h1v1h-1z",
                    FillRef.HAIR,
                ),
                PathSpec(
                    "M15 4h1v1h-1zM14 3h1v1h-1zM14 8h1v1h-1zM15 6h1v1h-1zM15 7h1v1h-1zM13 2h1v1h-1zM2 2h1v1H2zM3 1h1v1H3z",
                    FillRef.HAIR,
                ),
            ),
        "short10" to
            listOf(
                PathSpec("M3 2h10v1H3zM3 3h2v1H3zM7 1h5v1H7zM13 3h-1v1h1z", FillRef.HAIR),
            ),
        "short09" to
            listOf(
                PathSpec("M7 1h5v1H7zM4 2h7v1H4zM3 3h5v1H3z", FillRef.HAIR),
            ),
        "short08" to
            listOf(
                PathSpec("M5 1h5v1H5zM4 2h8v1H4zM9 3h1v1H9zM11 3h2v1h-2zM3 3h2v1H3z", FillRef.HAIR),
            ),
        "short07" to
            listOf(
                PathSpec("M3 3h1v1H3zM5 2H4v1h1zM12 2h-1v1h1zM13 3h-1v1h1z", FillRef.HAIR),
            ),
        "short06" to
            listOf(
                PathSpec("M4 2H3v3h1zM13 2h-1v3h1z", FillRef.HAIR),
                PathSpec("M12 1H3v2h9z", FillRef.HAIR),
                PathSpec("M4 2H2v2h2zM14 2h-2v2h2z", FillRef.HAIR),
            ),
        "short05" to
            listOf(
                PathSpec("M9 1H8v3h1zM8 2H7v2h1zM10 0H9v3h1zM11 1h-1v1h1z", FillRef.HAIR),
            ),
        "short04" to
            listOf(
                PathSpec("M4 3H3v2h1zM13 3h-1v2h1zM12 2H4v1h8z", FillRef.HAIR),
            ),
        "short03" to
            listOf(
                PathSpec("M12 2H4v1h8zM13 3h-1v1h1zM6 3H3v1h3zM5 4H3v1h2z", FillRef.HAIR),
            ),
        "short02" to
            listOf(
                PathSpec("M12 2H4v1h8zM13 3h-2v1h2zM13 4h-1v1h1zM6 3H3v1h3zM5 4H3v1h2zM4 5H3v1h1z", FillRef.HAIR),
            ),
        "short01" to
            listOf(
                PathSpec("M12 2H4v1h8zM13 3H7v1h6zM13 4h-2v1h2zM13 5h-1v1h1zM6 4H3v1h3zM7 3H3v1h4zM5 5H3v1h2zM4 6H3v1h1z", FillRef.HAIR),
            ),
        "long21" to
            listOf(
                PathSpec("M13 1H3v1H2v4h1V3h10v3h1V2h-1V1ZM2 8h1v3h1v1H2V8ZM12 11h1V8h1v4h-2v-1Z", FillRef.HAIR),
            ),
        "long20" to
            listOf(
                PathSpec("M4 1h8v1h1v1h1v3h-1V4h-1V3H6v1H3v2H2V3h1V2h1V1ZM2 8h1v3h1v1h3v1H2V8ZM12 12H9v1h5V8h-1v3h-1v1Z", FillRef.HAIR),
            ),
        "long19" to
            listOf(
                PathSpec("M13 2H3v1H2v3h1V4h10v2h1V3h-1V2ZM3 8H2v1H1v2H0v1h1v-1h1v-1h1V8Z", FillRef.HAIR),
            ),
        "long18" to
            listOf(
                PathSpec(
                    "M13 2H3v1H2v3h1V4h10v2h1V3h-1V2ZM2 8h1v2H2v1H1V9h1V8ZM1 11v1H0v-1h1ZM16 11h-1V9h-1V8h-1v2h1v1h1v1h1v-1Z",
                    FillRef.HAIR,
                ),
            ),
        "long17" to
            listOf(
                PathSpec("M5 1h7v1h1v1h1v3h-1V4h-2V3H5v1H4v1H3v1H2v2h1v3h1v1H1V4h1V3h1V2h2V1Z", FillRef.HAIR),
            ),
        "long16" to
            listOf(
                PathSpec("M3 1h8v1h2v1h1v3h-1V4h-2V3H6v1H5V3H4v1H3v2H2v2h1v2H1V3h1V2h1V1ZM14 8h-1v2h1V8Z", FillRef.HAIR),
            ),
        "long15" to
            listOf(
                PathSpec("M5 0h1v1H5V0Z", FillRef.HAIR),
                PathSpec("M2 3h1V1h2v1h1V1h1v1h1V1H7V0h2v2h1V1h1v1h1V1h1v1h1v1h-1v1H4v1H3v1H2V5H1V4h1V3Z", FillRef.HAIR),
                PathSpec("M2 3H1V2h1v1ZM1 6H0V5h1v1ZM2 8H1V6h1v2ZM2 8h1v2H2V8Z", FillRef.HAIR),
                PathSpec("M14 4h-1v2h2V5h1V4h-1V3h-1v1Zm0 0h1v1h-1V4Z", FillRef.HAIR, evenOdd = true),
                PathSpec("M12 1h-1V0h1v1ZM13 8h1v2h-1V8Z", FillRef.HAIR),
            ),
        "long14" to
            listOf(
                PathSpec("M5 1H3v1H2v2h1v1h1V4h8v1h1V4h1V2h-1V1h-2v1H5V1Z", FillRef.HAIR),
            ),
        "long13" to
            listOf(
                PathSpec("M13 1H3v1H2v1H1v11h2V4h10v10h2V3h-1V2h-1V1Z", FillRef.HAIR),
            ),
        "long12" to
            listOf(
                PathSpec(
                    "M5 4V3h7v1h1v2h1v2h-1v3h-1v1H9v1h4v1h1v3h1V3h-1V2h-1V1H3v1H2v1H1v14h1v-3h1v-1h4v-1H4v-1H3V8H2V6h1V5h1V4h1Z",
                    FillRef.HAIR,
                ),
            ),
        "long11" to
            listOf(
                PathSpec("M6 1v1H3v2H2v2h1V5h4V4h3V3h2v1h1v2h1V2h-2V1H6ZM2 8h1v2H2V8ZM14 8h-1v2h1V8Z", FillRef.HAIR),
            ),
        "long10" to
            listOf(
                PathSpec("M4 1v1H3v1H2v1H1v6h2V8H2V6h1V4h1V3h2v1h1V3h1V2H7V1H4Z", FillRef.HAIR),
            ),
        "long09" to
            listOf(
                PathSpec("M2 1h12v1h1v11h-1v-1h-1V8h1V6h-1V3H3v3H2v2h1v4H2v1H1V2h1V1Z", FillRef.HAIR),
            ),
        "long08" to
            listOf(
                PathSpec("M2 1h12v1h1v11h-1v-1h-1V8h1V6h-1V3h-1v1h-1V3H8v1H7v1H6v1H5v1H4v1H3v4H2v1H1V2h1V1Z", FillRef.HAIR),
            ),
        "long07" to
            listOf(
                PathSpec("M3 1h10v1h1v1h1v10h-1v-1h-1V8h1V6h-1V4h-1V3H5v1H4v1H3v1H2v2h1v4H2v1H1V3h1V2h1V1Z", FillRef.HAIR),
            ),
        "long06" to
            listOf(
                PathSpec("M3 2h10v1h1v3h-1V4H3v2H2V3h1V2ZM13 8h1v1h1v2h-1v-1h-1V8ZM15 11h1v1h-1v-1Z", FillRef.HAIR),
            ),
        "long05" to
            listOf(
                PathSpec("M4 0H1v3h2v1H2v2h1V5h2V4h1V3h5v1h1v1h1v1h1V3h-1V2h-1V1H6v1H4V0ZM2 8h1v1H2V8ZM14 8h-1v1h1V8Z", FillRef.HAIR),
            ),
        "long04" to
            listOf(
                PathSpec("M12 1H5v1H4v1H3v1H2v2h2V5h1V4h1V3h5v1h1v1h1v1h1V3h-1V2h-1V1ZM13 8h1v4h-2v-1h1V8ZM2 8h1v3h1v1H2V8Z", FillRef.HAIR),
            ),
        "long03" to
            listOf(
                PathSpec("M4 2h8v1h1v2h1v6h1v2h-2v-1h-1V5h-1V4H5v1H4v7H3v1H1v-2h1V5h1V3h1V2Z", FillRef.HAIR),
            ),
        "long02" to
            listOf(
                PathSpec("M4 2h8v1h1v2h1v7h-1v-1h-1V5h-1V4H5v1H4v6H3v1H2V5h1V3h1V2ZM2 12v1H1v-1h1ZM14 12h1v1h-1v-1Z", FillRef.HAIR),
            ),
        "long01" to
            listOf(
                PathSpec(
                    "M12 1H5v1H4v1H3v1H2v2h2V5h1V4h1V3h5v1h1v1h1v1h1V3h-1V2h-1V1ZM13 8h1v6h-1v-1H9v-1h3v-1h1V8ZM2 8h1v3h1v1h3v1H3v1H2V8Z",
                    FillRef.HAIR,
                ),
            ),
    )

private val hatMap: Map<String, List<PathSpec>> =
    mapOf(
        "variant10" to
            listOf(
                PathSpec("M12 0H4v2H2v3h12V2h-2V0Z", FillRef.HAT),
                PathSpec("M10 2h1v3h-1zM8 3h1v2H8z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant09" to
            listOf(
                PathSpec("M4 0h8v1h1v1h1v3H2V2h1V1h1V0Z", FillRef.HAT),
                PathSpec("M11 2h1v3h-1zM9 2h1v3H9z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant08" to
            listOf(
                PathSpec("M4 0h8v1h1v1h1v2H2V2h1V1h1V0Z", FillRef.HAT),
                PathSpec("M12 2v1H9V2z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant07" to
            listOf(
                PathSpec("M12 1H4v1H3v2h12V3h-2V2h-1V1Z", FillRef.HAT),
            ),
        "variant06" to
            listOf(
                PathSpec("M13 0H3v3H1v1h14V3h-2V0Z", FillRef.HAT),
                PathSpec("M11 1h1v3h-1z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant05" to
            listOf(
                PathSpec("M4 0h8v1h1v1h1v2H2V2h1V1h1V0Z", FillRef.HAT),
                PathSpec(
                    "M12 1h1v1h-1zM11 2h1v1h-1zM10 3h1v1h-1zM10 0h1v1h-1zM9 1h1v1H9zM8 2h1v1H8zM7 3h1v1H7z",
                    FillRef.WHITE,
                    alpha = 0.3f,
                ),
            ),
        "variant04" to
            listOf(
                PathSpec("M4 0h8v1h1v1h1v3H2V2h1V1h1V0Z", FillRef.HAT),
                PathSpec("M13 1v1H3V1zM14 3v1H2V3z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant03" to
            listOf(
                PathSpec("M4 0h8v1h1v1h1v2H2V2h1V1h1V0Z", FillRef.HAT),
                PathSpec("M13 1v1H3V1zM14 3v1H2V3z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant02" to
            listOf(
                PathSpec("M12 1H4v1H3v2h12V3h-2V2h-1V1Z", FillRef.HAT),
                PathSpec("M11 4h-1V1h1z", FillRef.WHITE, alpha = 0.3f),
            ),
        "variant01" to
            listOf(
                PathSpec("M4 0h8v1h1v1h1v3H2V2h1V1h1V0Z", FillRef.HAT),
                PathSpec("M5 0h1v1H5zM4 1h1v1H4zM3 2h1v1H3zM2 3h1v1H2z", FillRef.WHITE, alpha = 0.3f),
            ),
    )

private val mouthMap: Map<String, List<PathSpec>> =
    mapOf(
        "sad10" to
            listOf(
                PathSpec("M7 9v1h1v1h1V9H7Z", FillRef.MOUTH),
            ),
        "sad09" to
            listOf(
                PathSpec("M6 10v1h1v-1h3V9H7v1H6Z", FillRef.MOUTH),
            ),
        "sad08" to
            listOf(
                PathSpec("M6 9v1h3v1h1v-1H9V9H6Z", FillRef.MOUTH),
            ),
        "sad07" to
            listOf(
                PathSpec("M7 9h2v1H7V9Z", FillRef.MOUTH),
            ),
        "sad06" to
            listOf(
                PathSpec("M6 9h4v1H6V9Z", FillRef.MOUTH),
            ),
        "sad05" to
            listOf(
                PathSpec("M7 9h3v1H7V9Z", FillRef.MOUTH),
            ),
        "sad04" to
            listOf(
                PathSpec("M6 9h3v1H6V9Z", FillRef.MOUTH),
            ),
        "sad03" to
            listOf(
                PathSpec("M9 9v1H7v1H6v-1h1V9h2Z", FillRef.MOUTH),
            ),
        "sad02" to
            listOf(
                PathSpec("M7 9v1h2v1h1v-1H9V9H7Z", FillRef.MOUTH),
            ),
        "sad01" to
            listOf(
                PathSpec("M7 9h2v1H7V9ZM7 10v1H6v-1h1ZM9 10v1h1v-1H9Z", FillRef.MOUTH),
            ),
        "happy13" to
            listOf(
                PathSpec("M6 10v1h1v1h2v-1h1v-1H9V9H7v1H6Z", FillRef.MOUTH),
                PathSpec("M7 10v1h2v-1H7Z", FillRef.WHITE, alpha = 0.2f),
            ),
        "happy12" to
            listOf(
                PathSpec("M6 10v1h1v1h2v-1h1v-1H9V9H7v1H6Z", FillRef.MOUTH),
                PathSpec("M7 10v1h2v-1H7Z", FillRef.WHITE),
            ),
        "happy11" to
            listOf(
                PathSpec("M6 9v1h1v1h2v-1H7V9H6Z", FillRef.MOUTH),
            ),
        "happy10" to
            listOf(
                PathSpec("M10 9v1H9v1H7v-1h2V9h1Z", FillRef.MOUTH),
            ),
        "happy09" to
            listOf(
                PathSpec("M7 11h2v-1H7v1ZM7 10V9H6v1h1ZM9 10V9h1v1H9Z", FillRef.MOUTH),
            ),
        "happy08" to
            listOf(
                PathSpec("M6 11v-1h3V9h1v1H9v1H6Z", FillRef.MOUTH),
            ),
        "happy07" to
            listOf(
                PathSpec("M10 11v-1H7V9H6v1h1v1h3Z", FillRef.MOUTH),
            ),
        "happy06" to
            listOf(
                PathSpec("M6 9v1h1v1h2v-1h1V9H6Z", FillRef.MOUTH),
            ),
        "happy05" to
            listOf(
                PathSpec("M7 9v2h2v-1H8V9H7Z", FillRef.MOUTH),
                PathSpec("M9 11v-1H8V9H7v1h1v1h1Z", FillRef.WHITE, alpha = 0.2f),
            ),
        "happy04" to
            listOf(
                PathSpec("M9 9v1H8v1h2V9H9Z", FillRef.MOUTH),
            ),
        "happy03" to
            listOf(
                PathSpec("M7 9v1h1v1H6V9h1Z", FillRef.MOUTH),
            ),
        "happy02" to
            listOf(
                PathSpec("M8 10v1h1v-1H8Z", FillRef.MOUTH),
            ),
        "happy01" to
            listOf(
                PathSpec("M7 9v2h2V9H7Z", FillRef.MOUTH),
            ),
    )
