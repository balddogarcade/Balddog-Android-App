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
import kotlin.math.floor

/**
 * Offline implementation of DiceBear Pixel Art (v9.4.2, CC0 / MIT)
 * Generates deterministic 16x16 pixel-art avatars from a seed (e.g. user email)
 * with zero network requests.
 */
@Composable
fun DiceBearPixelArtAvatar(
    seed: String,
    modifier: Modifier = Modifier
) {
    val layers = remember(seed) { buildPixelArtLayers(seed) }
    Canvas(modifier = modifier) {
        scale(
            scaleX = size.width / 16f,
            scaleY = size.height / 16f,
            pivot = Offset.Zero
        ) {
            for (layer in layers) {
                drawPath(
                    path = layer.path,
                    color = layer.color,
                    alpha = layer.alpha
                )
            }
        }
    }
}

private data class ResolvedLayer(
    val path: Path,
    val color: Color,
    val alpha: Float
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
    data class HEX(val argb: Long) : FillRef
}

private data class PathSpec(
    val d: String,
    val fill: FillRef,
    val alpha: Float = 1f,
    val evenOdd: Boolean = false
)

private data class AvatarColors(
    val accessories: Color,
    val clothing: Color,
    val eyes: Color,
    val glasses: Color,
    val hair: Color,
    val hat: Color,
    val mouth: Color,
    val skin: Color
)

private class DiceBearPrng(seed: String) {
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

    fun integer(min: Int, max: Int): Int {
        val ratio = (next().toDouble() - (-2147483648.0)) / (2147483647.0 - (-2147483648.0))
        return floor(ratio * (max + 1 - min) + min).toInt().coerceIn(min, max)
    }

    fun bool(likelihood: Int = 50): Boolean = integer(1, 100) <= likelihood

    fun <T> pick(items: List<T>, fallback: T): T {
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

private fun resolveFill(ref: FillRef, colors: AvatarColors): Color = when (ref) {
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

    val showAccessories = prng.bool(10)
    val showGlasses = prng.bool(20)
    val showBeard = prng.bool(5)
    val showHat = prng.bool(5)

    // Pick colors in exact DiceBear pixel-art order
    val colors = AvatarColors(
        accessories = hexToColor(prng.pick(ACCESSORIES_COLORS, "transparent")),
        clothing = hexToColor(prng.pick(CLOTHING_COLORS, "transparent")),
        eyes = hexToColor(prng.pick(EYES_COLORS, "transparent")),
        glasses = hexToColor(prng.pick(GLASSES_COLORS, "transparent")),
        hair = hexToColor(prng.pick(HAIR_COLORS, "transparent")),
        hat = hexToColor(prng.pick(HAT_COLORS, "transparent")),
        mouth = hexToColor(prng.pick(MOUTH_COLORS, "transparent")),
        skin = hexToColor(prng.pick(SKIN_COLORS, "transparent"))
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
        val path = PathParser().parsePathString(spec.d).toPath().apply {
            if (spec.evenOdd) fillType = PathFillType.EvenOdd
        }
        ResolvedLayer(
            path = path,
            color = resolveFill(spec.fill, colors),
            alpha = spec.alpha
        )
    }
}

private val ACCESSORIES_KEYS = listOf("variant04", "variant03", "variant02", "variant01")
private val ACCESSORIES_COLORS = listOf("daa520", "ffd700", "fafad2", "d3d3d3", "a9a9a9")
private val BEARD_KEYS = listOf("variant08", "variant07", "variant06", "variant05", "variant04", "variant03", "variant02", "variant01")
private val CLOTHING_KEYS = listOf(
    "variant23", "variant22", "variant21", "variant20", "variant19", "variant18",
    "variant17", "variant16", "variant15", "variant14", "variant13", "variant12",
    "variant11", "variant10", "variant09", "variant08", "variant07", "variant06",
    "variant05", "variant04", "variant03", "variant02", "variant01"
)
private val CLOTHING_COLORS = listOf(
    "5bc0de", "428bca", "03396c", "88d8b0", "44c585", "00b159",
    "ff6f69", "d11141", "ae0001", "ffeead", "ffd969", "ffc425"
)
private val EYES_KEYS = listOf(
    "variant12", "variant11", "variant10", "variant09", "variant08", "variant07",
    "variant06", "variant05", "variant04", "variant03", "variant02", "variant01"
)
private val EYES_COLORS = listOf("76778b", "697b94", "647b90", "5b7c8b", "588387", "876658")
private val GLASSES_KEYS = listOf(
    "light07", "light06", "light05", "light04", "light03", "light02", "light01",
    "dark07", "dark06", "dark05", "dark04", "dark03", "dark02", "dark01"
)
private val GLASSES_COLORS = listOf("4b4b4b", "323232", "191919", "43677d", "5f705c", "a04b5d")
private val HAIR_KEYS = listOf(
    "short24", "short23", "short22", "short21", "short20", "short19", "short18", "short17",
    "short16", "short15", "short14", "short13", "short12", "short11", "short10", "short09",
    "short08", "short07", "short06", "short05", "short04", "short03", "short02", "short01",
    "long21", "long20", "long19", "long18", "long17", "long16", "long15", "long14",
    "long13", "long12", "long11", "long10", "long09", "long08", "long07", "long06",
    "long05", "long04", "long03", "long02", "long01"
)
private val HAIR_COLORS = listOf(
    "cab188", "603a14", "83623b", "a78961", "611c17", "603015",
    "612616", "28150a", "009bbd", "bd1700", "91cb15"
)
private val HAT_KEYS = listOf(
    "variant10", "variant09", "variant08", "variant07", "variant06",
    "variant05", "variant04", "variant03", "variant02", "variant01"
)
private val HAT_COLORS = listOf("2e1e05", "2663a3", "989789", "3d8a6b", "cc6192", "614f8a", "a62116")
private val MOUTH_KEYS = listOf(
    "sad10", "sad09", "sad08", "sad07", "sad06", "sad05", "sad04", "sad03", "sad02", "sad01",
    "happy13", "happy12", "happy11", "happy10", "happy09", "happy08", "happy07", "happy06",
    "happy05", "happy04", "happy03", "happy02", "happy01"
)
private val MOUTH_COLORS = listOf("d29985", "c98276", "e35d6a", "de0f0d")
private val SKIN_COLORS = listOf("ffdbac", "f5cfa0", "eac393", "e0b687", "cb9e6e", "b68655", "a26d3d", "8d5524")

private val accessoriesMap: Map<String, List<PathSpec>> = mapOf(
    "variant04" to listOf(
    ),
    "variant03" to listOf(
    ),
    "variant02" to listOf(
    ),
    "variant01" to listOf(
    ),
)

private val beardMap: Map<String, List<PathSpec>> = mapOf(
    "variant08" to listOf(
    ),
    "variant07" to listOf(
    ),
    "variant06" to listOf(
    ),
    "variant05" to listOf(
    ),
    "variant04" to listOf(
    ),
    "variant03" to listOf(
    ),
    "variant02" to listOf(
    ),
    "variant01" to listOf(
    ),
)

private val clothingMap: Map<String, List<PathSpec>> = mapOf(
    "variant23" to listOf(
    ),
    "variant22" to listOf(
    ),
    "variant21" to listOf(
    ),
    "variant20" to listOf(
    ),
    "variant19" to listOf(
    ),
    "variant18" to listOf(
    ),
    "variant17" to listOf(
    ),
    "variant16" to listOf(
    ),
    "variant15" to listOf(
    ),
    "variant14" to listOf(
    ),
    "variant13" to listOf(
    ),
    "variant12" to listOf(
    ),
    "variant11" to listOf(
    ),
    "variant10" to listOf(
    ),
    "variant09" to listOf(
    ),
    "variant08" to listOf(
    ),
    "variant07" to listOf(
    ),
    "variant06" to listOf(
    ),
    "variant05" to listOf(
    ),
    "variant04" to listOf(
    ),
    "variant03" to listOf(
    ),
    "variant02" to listOf(
    ),
    "variant01" to listOf(
    ),
)

private val eyesMap: Map<String, List<PathSpec>> = mapOf(
    "variant12" to listOf(
        PathSpec("M5 6h1v2H4V7h1V6Zm5 0h1v2H9V7h1V6Z", FillRef.WHITE, evenOdd = true),
    ),
    "variant11" to listOf(
        PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE),
    ),
    "variant10" to listOf(
        PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE),
    ),
    "variant09" to listOf(
        PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE),
    ),
    "variant08" to listOf(
        PathSpec("M4 5h3v2H4zM9 5h3v2H9z", FillRef.WHITE),
    ),
    "variant07" to listOf(
        PathSpec("M4 5h3v2H4zM9 5h3v2H9z", FillRef.WHITE),
    ),
    "variant06" to listOf(
        PathSpec("M4 5h3v2H4z", FillRef.WHITE),
    ),
    "variant05" to listOf(
        PathSpec("M4 5h3v2H4zM9 5h3v2H9z", FillRef.WHITE),
    ),
    "variant04" to listOf(
        PathSpec("M6 5H5v2h2V6H6V5ZM11 5h-1v2h2V6h-1V5Z", FillRef.WHITE),
    ),
    "variant03" to listOf(
        PathSpec("M10 5h1v2H9V6h1V5ZM5 5h1v2H4V6h1V5Z", FillRef.WHITE),
    ),
    "variant02" to listOf(
        PathSpec("M11 5h-1v2h2V6h-1V5ZM6 5H5v2h2V6H6V5Z", FillRef.WHITE, evenOdd = true),
    ),
    "variant01" to listOf(
        PathSpec("M12 5H9v3h3zM7 5H4v3h3z", FillRef.WHITE),
    ),
)

private val glassesMap: Map<String, List<PathSpec>> = mapOf(
    "light07" to listOf(
    ),
    "light06" to listOf(
    ),
    "light05" to listOf(
    ),
    "light04" to listOf(
        PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.WHITE, alpha = 0.3f),
    ),
    "light03" to listOf(
    ),
    "light02" to listOf(
    ),
    "light01" to listOf(
    ),
    "dark07" to listOf(
    ),
    "dark06" to listOf(
    ),
    "dark05" to listOf(
    ),
    "dark04" to listOf(
        PathSpec("M4 5h3v3H4zM9 5h3v3H9z", FillRef.BLACK, alpha = 0.3f),
    ),
    "dark03" to listOf(
    ),
    "dark02" to listOf(
    ),
    "dark01" to listOf(
    ),
)

private val hairMap: Map<String, List<PathSpec>> = mapOf(
    "short24" to listOf(
    ),
    "short23" to listOf(
    ),
    "short22" to listOf(
    ),
    "short21" to listOf(
    ),
    "short20" to listOf(
    ),
    "short19" to listOf(
    ),
    "short18" to listOf(
    ),
    "short17" to listOf(
    ),
    "short16" to listOf(
    ),
    "short15" to listOf(
    ),
    "short14" to listOf(
    ),
    "short13" to listOf(
    ),
    "short12" to listOf(
    ),
    "short11" to listOf(
    ),
    "short10" to listOf(
    ),
    "short09" to listOf(
    ),
    "short08" to listOf(
    ),
    "short07" to listOf(
    ),
    "short06" to listOf(
    ),
    "short05" to listOf(
    ),
    "short04" to listOf(
    ),
    "short03" to listOf(
    ),
    "short02" to listOf(
    ),
    "short01" to listOf(
    ),
    "long21" to listOf(
    ),
    "long20" to listOf(
    ),
    "long19" to listOf(
    ),
    "long18" to listOf(
    ),
    "long17" to listOf(
    ),
    "long16" to listOf(
    ),
    "long15" to listOf(
    ),
    "long14" to listOf(
    ),
    "long13" to listOf(
    ),
    "long12" to listOf(
    ),
    "long11" to listOf(
    ),
    "long10" to listOf(
    ),
    "long09" to listOf(
    ),
    "long08" to listOf(
    ),
    "long07" to listOf(
    ),
    "long06" to listOf(
    ),
    "long05" to listOf(
    ),
    "long04" to listOf(
    ),
    "long03" to listOf(
    ),
    "long02" to listOf(
    ),
    "long01" to listOf(
    ),
)

private val hatMap: Map<String, List<PathSpec>> = mapOf(
    "variant10" to listOf(
    ),
    "variant09" to listOf(
    ),
    "variant08" to listOf(
    ),
    "variant07" to listOf(
    ),
    "variant06" to listOf(
    ),
    "variant05" to listOf(
    ),
    "variant04" to listOf(
    ),
    "variant03" to listOf(
    ),
    "variant02" to listOf(
    ),
    "variant01" to listOf(
    ),
)

private val mouthMap: Map<String, List<PathSpec>> = mapOf(
    "sad10" to listOf(
    ),
    "sad09" to listOf(
    ),
    "sad08" to listOf(
    ),
    "sad07" to listOf(
    ),
    "sad06" to listOf(
    ),
    "sad05" to listOf(
    ),
    "sad04" to listOf(
    ),
    "sad03" to listOf(
    ),
    "sad02" to listOf(
    ),
    "sad01" to listOf(
    ),
    "happy13" to listOf(
    ),
    "happy12" to listOf(
    ),
    "happy11" to listOf(
    ),
    "happy10" to listOf(
    ),
    "happy09" to listOf(
    ),
    "happy08" to listOf(
    ),
    "happy07" to listOf(
    ),
    "happy06" to listOf(
    ),
    "happy05" to listOf(
    ),
    "happy04" to listOf(
    ),
    "happy03" to listOf(
    ),
    "happy02" to listOf(
    ),
    "happy01" to listOf(
    ),
)
