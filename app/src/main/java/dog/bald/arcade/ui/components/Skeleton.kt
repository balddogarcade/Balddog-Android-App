package dog.bald.arcade.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface

fun Modifier.shimmerEffect(): Modifier =
    composed {
        val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
        val progress by transition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1150, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "skeleton_shimmer_progress",
        )

        val shimmerColors =
            listOf(
                GraphiteSurface,
                CharcoalBorder,
                GraphiteSurface,
            )

        background(
            brush =
                Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(x = progress * 600f, y = 0f),
                    end = Offset(x = (progress + 1f) * 600f, y = 300f),
                ),
        )
    }

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .shimmerEffect(),
    )
}
