package dog.bald.arcade.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SquareCornerShape = RoundedCornerShape(0.dp)

private val ArcadeShapes = Shapes(
    extraSmall = SquareCornerShape,
    small = SquareCornerShape,
    medium = SquareCornerShape,
    large = SquareCornerShape,
    extraLarge = SquareCornerShape
)

private val ArcadeMonochromeColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    secondary = SilverGray,
    onSecondary = PureBlack,
    tertiary = GraphiteSurface,
    onTertiary = PureWhite,
    background = ObsidianBlack,
    onBackground = PureWhite,
    surface = CarbonSurface,
    onSurface = PureWhite,
    surfaceVariant = GraphiteSurface,
    onSurfaceVariant = MutedGray,
    outline = SteelBorder,
    outlineVariant = CharcoalBorder,
)

@Composable
fun BaldDogArcadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArcadeMonochromeColorScheme,
        typography = BaldDogTypography,
        shapes = ArcadeShapes,
        content = content
    )
}
