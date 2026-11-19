package dog.bald.arcade.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ArcadeDarkColorScheme = darkColorScheme(
    primary = CoinYellow,
    onPrimary = ArcadeBlack,
    secondary = ArcadeMagenta,
    onSecondary = RetroIvory,
    tertiary = ArcadeOrange,
    onTertiary = ArcadeBlack,
    background = ArcadeBlack,
    onBackground = RetroIvory,
    surface = LeatherBlack,
    onSurface = RetroIvory,
    surfaceVariant = DeepPurple,
    onSurfaceVariant = SteelGray,
    outline = ArcadeMagenta,
)

@Composable
fun BaldDogArcadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArcadeDarkColorScheme,
        content = content
    )
}
