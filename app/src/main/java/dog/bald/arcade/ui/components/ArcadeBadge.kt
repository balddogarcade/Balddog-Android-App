package dog.bald.arcade.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder

enum class ArcadeBadgeStyle {
    Solid,
    Outline,
    Overlay
}

@Composable
fun ArcadeBadge(
    text: String,
    modifier: Modifier = Modifier,
    style: ArcadeBadgeStyle = ArcadeBadgeStyle.Outline
) {
    val shape = RoundedCornerShape(6.dp)
    val bgColor = when (style) {
        ArcadeBadgeStyle.Solid -> PureWhite
        ArcadeBadgeStyle.Outline -> GraphiteSurface
        ArcadeBadgeStyle.Overlay -> ObsidianBlack.copy(alpha = 0.84f)
    }
    val borderColor = when (style) {
        ArcadeBadgeStyle.Solid -> PureWhite
        ArcadeBadgeStyle.Outline -> CharcoalBorder
        ArcadeBadgeStyle.Overlay -> SteelBorder
    }
    val textColor = when (style) {
        ArcadeBadgeStyle.Solid -> PureBlack
        ArcadeBadgeStyle.Outline -> SilverGray
        ArcadeBadgeStyle.Overlay -> PureWhite
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
            maxLines = 1
        )
    }
}
