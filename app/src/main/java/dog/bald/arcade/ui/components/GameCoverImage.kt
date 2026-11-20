package dog.bald.arcade.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.MutedGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object CoverCache {
    private val cache = LruCache<String, ImageBitmap>(48)
    fun get(slug: String): ImageBitmap? = cache.get(slug)
    fun put(slug: String, bmp: ImageBitmap) {
        cache.put(slug, bmp)
    }
}

@Composable
fun GameCoverImage(
    slug: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = RoundedCornerShape(8.dp),
    backgroundColor: Color = GraphiteSurface,
    showBorder: Boolean = true
) {
    val context = LocalContext.current

    val bitmap by produceState<ImageBitmap?>(initialValue = slug?.let { CoverCache.get(it) }, slug) {
        if (slug.isNullOrBlank()) {
            value = null
            return@produceState
        }
        val cached = CoverCache.get(slug)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("covers/$slug.webp").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()?.also { bmp ->
                        CoverCache.put(slug, bmp)
                    }
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .then(if (showBorder) Modifier.border(1.dp, CharcoalBorder, shape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.SportsEsports,
                contentDescription = contentDescription,
                tint = MutedGray
            )
        }
    }
}
