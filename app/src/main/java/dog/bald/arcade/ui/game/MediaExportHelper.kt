package dog.bald.arcade.ui.game

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaExportHelper {
    private fun ensureLegacyWritePermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                throw SecurityException("Permiso WRITE_EXTERNAL_STORAGE requerido en Android 8–9")
            }
        }
    }

    suspend fun savePngToGallery(
        context: Context,
        sourcePng: File,
        title: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                ensureLegacyWritePermission(context)
                val safeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
                val filename = "balddog_${safeTitle}_${System.currentTimeMillis()}.png"
                val resolver = context.contentResolver
                val values =
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/balddog ARCADE")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                val uri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("No se pudo crear entrada en MediaStore")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourcePng.inputStream().use { input -> input.copyTo(out) }
                    } ?: error("No se pudo abrir flujo de escritura")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                Result.success(filename)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun saveMp4ToGallery(
        context: Context,
        sourceMp4: File,
        title: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                ensureLegacyWritePermission(context)
                val safeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
                val filename = "balddog_clip_${safeTitle}_${System.currentTimeMillis()}.mp4"
                val resolver = context.contentResolver
                val values =
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/balddog ARCADE")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                val uri =
                    resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("No se pudo crear entrada de video en MediaStore")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceMp4.inputStream().use { input -> input.copyTo(out) }
                    } ?: error("No se pudo abrir flujo de escritura de video")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                Result.success(filename)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
}
