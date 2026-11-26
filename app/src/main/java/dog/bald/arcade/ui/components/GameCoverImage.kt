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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    private const val MAX_MEMORY_KB = 24 * 1024
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val MAX_DECODE_DIM = 1024

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val inFlightLocks = ConcurrentHashMap<String, Mutex>()

    private val memoryCache =
        object : LruCache<String, ImageBitmap>(MAX_MEMORY_KB) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).toInt().coerceAtLeast(1)
        }

    fun getFromMemory(url: String): ImageBitmap? = memoryCache.get(url)

    suspend fun load(
        context: Context,
        url: String,
    ): ImageBitmap? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        memoryCache.get(url)?.let { return it }
        val lock = inFlightLocks.computeIfAbsent(url) { Mutex() }
        return lock.withLock {
            memoryCache.get(url)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                runCatching {
                    val cacheDir = File(context.cacheDir, "image_cache").apply { mkdirs() }
                    val cacheFile = File(cacheDir, sha256(url))

                    if (cacheFile.exists() && cacheFile.length() > 0L) {
                        decodeSampledFile(cacheFile)?.asImageBitmap()?.also { bmp ->
                            memoryCache.put(url, bmp)
                            return@runCatching bmp
                        }
                    }

                    val req = Request.Builder().url(url).build()
                    val bytes =
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            val body = resp.body ?: return@use null
                            if (body.contentLength() > MAX_IMAGE_BYTES) return@use null
                            val raw = body.bytes()
                            if (raw.size > MAX_IMAGE_BYTES) return@use null
                            raw
                        } ?: return@runCatching null

                    val bmp = decodeSampledBytes(bytes)?.asImageBitmap() ?: return@runCatching null
                    runCatching {
                        val tmp = File(cacheDir, "${cacheFile.name}.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(cacheFile)) {
                            tmp.copyTo(cacheFile, overwrite = true)
                            tmp.delete()
                        }
                    }
                    memoryCache.put(url, bmp)
                    bmp
                }.getOrNull()
            }
        }
    }

    private fun decodeSampledBytes(bytes: ByteArray): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun decodeSampledFile(file: File): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
    ): Int {
        var inSampleSize = 1
        while (width / inSampleSize > MAX_DECODE_DIM || height / inSampleSize > MAX_DECODE_DIM) {
            inSampleSize *= 2
        }
        return inSampleSize
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
    showBorder: Boolean = true,
) {
    val context = LocalContext.current
    val resolvedUrl = coverUrl?.takeIf { it.isNotBlank() } ?: CoverUrlRegistry.resolve(slug)

    val bitmap by produceState<ImageBitmap?>(
        initialValue = resolvedUrl?.let { RemoteImageCache.getFromMemory(it) },
        resolvedUrl,
    ) {
        if (resolvedUrl.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = RemoteImageCache.load(context, resolvedUrl)
    }

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(backgroundColor)
                .then(if (showBorder) Modifier.border(1.dp, CharcoalBorder, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.SportsEsports,
                contentDescription = contentDescription,
                tint = MutedGray,
            )
        }
    }
}
