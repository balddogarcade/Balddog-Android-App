package dog.bald.arcade.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dog.bald.arcade.data.GameDto

data class GalleryImageItem(
    val type: String,
    val label: String,
    val url: String,
)

@Composable
fun GameDetailGallery(
    game: GameDto,
    onImageOpened: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val images =
        remember(game) {
            listOfNotNull(
                game.urlImageIngame?.let { GalleryImageItem("ingame", "In-Game", it) },
                game.urlImageTitle?.let { GalleryImageItem("title", "Título", it) },
                game.urlImageCabinet?.let { GalleryImageItem("cabinet", "Gabinete", it) },
                game.urlImageFlyer?.let { GalleryImageItem("flyer", "Flyer", it) },
            )
        }

    if (images.isEmpty()) return

    var selectedImage by remember { mutableStateOf<GalleryImageItem?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Galería",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(images, key = { it.type }) { item ->
                RemoteImage(
                    url = item.url,
                    contentDescription = "${game.title} ${item.label}",
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(width = 160.dp, height = 112.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedImage = item
                                onImageOpened(item.type)
                            },
                )
            }
        }
    }

    selectedImage?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedImage = null },
            confirmButton = {
                TextButton(onClick = { selectedImage = null }) {
                    Text("Cerrar")
                }
            },
            title = { Text("${game.title} — ${item.label}") },
            text = {
                RemoteImage(
                    url = item.url,
                    contentDescription = item.label,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(8.dp)),
                )
            },
        )
    }
}
