package dog.bald.arcade.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dog.bald.arcade.data.GameDto

@Composable
internal fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val state by produceState<RemoteImageState>(
        initialValue =
            url
                ?.takeIf { it.isNotBlank() }
                ?.let { RemoteImageCache.getFromMemory(it) }
                ?.let { RemoteImageState.Success(it) }
                ?: if (url.isNullOrBlank()) RemoteImageState.Error else RemoteImageState.Loading,
        url,
    ) {
        if (url.isNullOrBlank()) {
            value = RemoteImageState.Error
            return@produceState
        }
        RemoteImageCache.getFromMemory(url)?.let {
            value = RemoteImageState.Success(it)
            return@produceState
        }
        value = RemoteImageState.Loading
        val loaded = RemoteImageCache.load(context, url)
        value = if (loaded != null) RemoteImageState.Success(loaded) else RemoteImageState.Error
    }

    when (val current = state) {
        is RemoteImageState.Success -> {
            Image(
                bitmap = current.bitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier,
            )
        }
        RemoteImageState.Loading -> {
            SkeletonBox(modifier = modifier)
        }
        RemoteImageState.Error -> {
            Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

@Composable
fun GameDetailHeader(
    game: GameDto,
    onPlayClick: () -> Unit,
    onRankingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!game.urlImageMarquee.isNullOrBlank()) {
            RemoteImage(
                url = game.urlImageMarquee,
                contentDescription = "${game.title} marquee",
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = game.coverUrl ?: game.urlImageTitle ?: game.urlIcon,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(width = 104.dp, height = 136.dp)
                        .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val subtitle =
                    listOfNotNull(
                        game.manufacturer?.takeIf { it.isNotBlank() },
                        game.year?.takeIf { it.isNotBlank() },
                    ).joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!game.serie.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = game.serie,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPlayClick,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Jugar")
            }
            OutlinedButton(
                onClick = onRankingClick,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.EmojiEvents, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ranking 1CC")
            }
        }
    }
}
