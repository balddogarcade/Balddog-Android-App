package dog.bald.arcade.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.MutedGray
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CoverUrlRegistry {
    private val urlsBySlug = ConcurrentHashMap<String, String>()

    fun registerAll(games: List<GameDto>) {
        for (game in games) {
            val url = game.coverUrl ?: game.urlImageTitle ?: game.urlIcon
            if (!url.isNullOrBlank()) {
                urlsBySlug[game.slug] = url
            }
        }
    }

    fun resolve(slug: String?): String? = slug?.let { urlsBySlug[it] }
}

internal object RemoteImageCache {
    private val memoryCache = LruCache<String, ImageBitmap>(64)

    fun getFromMemory(url: String): ImageBitmap? = memoryCache.get(url)

    suspend fun load(context: Context, url: String): ImageBitmap? {
        memoryCache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val cacheDir = File(context.cacheDir, "image_cache").apply { mkdirs() }
                val cacheFile = File(cacheDir, sha256(url))

                if (cacheFile.exists() && cacheFile.length() > 0L) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)?.asImageBitmap()?.also { bmp ->
                        memoryCache.put(url, bmp)
                        return@runCatching bmp
                    }
                }

                val bytes = URL(url).openStream().use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    ?: return@runCatching null
                runCatching { cacheFile.writeBytes(bytes) }
                memoryCache.put(url, bmp)
                bmp
            }.getOrNull()
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun GameCoverImage(
    slug: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = RectangleShape,
    backgroundColor: Color = GraphiteSurface,
    showBorder: Boolean = true
) {
    val context = LocalContext.current
    val resolvedUrl = coverUrl?.takeIf { it.isNotBlank() } ?: CoverUrlRegistry.resolve(slug)

    val bitmap by produceState<ImageBitmap?>(
        initialValue = resolvedUrl?.let { RemoteImageCache.getFromMemory(it) },
        resolvedUrl
    ) {
        if (resolvedUrl.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = RemoteImageCache.load(context, resolvedUrl)
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
