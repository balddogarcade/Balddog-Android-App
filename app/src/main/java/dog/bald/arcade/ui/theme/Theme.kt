package dog.bald.arcade.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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
        content = content
    )
}
