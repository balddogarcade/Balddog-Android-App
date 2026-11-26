package dog.bald.arcade.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dog.bald.arcade.BuildConfig
import dog.bald.arcade.diagnostics.CrashlyticsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val changelog: String,
    val apkUrl: String,
    val sha256Url: String?,
    val sizeBytes: Long,
    val publishedAt: String
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateState
    data object Verifying : AppUpdateState
    data class ReadyToInstall(val file: File) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList()
)

@Serializable
internal data class GitHubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L
)

class AppUpdateManager(
    private val context: Context
) {
    private val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    suspend fun checkForUpdates(): AppUpdateState = withContext(Dispatchers.IO) {
        _state.value = AppUpdateState.Checking
        CrashlyticsHelper.log("AppUpdateManager: checking latest release")
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "balddog-arcade-android/${BuildConfig.VERSION_NAME}")
                .build()

            val bodyStr = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub API HTTP ${response.code}")
                }
                response.body?.string() ?: throw IllegalStateException("Respuesta vacía de GitHub")
            }

            val release = json.decodeFromString<GitHubReleaseDto>(bodyStr)
            if (release.draft || release.prerelease) {
                _state.value = AppUpdateState.UpToDate
                return@withContext _state.value
            }

            val remoteVersion = release.tagName.removePrefix("v").trim()
            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
                _state.value = AppUpdateState.UpToDate
                return@withContext _state.value
            }

            val apkAsset = release.assets.firstOrNull { it.name == APK_ASSET_NAME }
                ?: release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: throw IllegalStateException("No se encontró $APK_ASSET_NAME en el release")

            val shaAsset = release.assets.firstOrNull { it.name == SHA256_ASSET_NAME }
                ?: release.assets.firstOrNull { it.name.endsWith(".sha256") }

            val info = AppUpdateInfo(
                versionName = remoteVersion,
                tagName = release.tagName,
                changelog = release.body?.trim().orEmpty().ifBlank { "Nueva versión disponible: v$remoteVersion" },
                apkUrl = apkAsset.browserDownloadUrl,
                sha256Url = shaAsset?.browserDownloadUrl,
                sizeBytes = apkAsset.size,
                publishedAt = release.publishedAt
            )
            CrashlyticsHelper.log("AppUpdateManager: update available ${info.tagName}")
            _state.value = AppUpdateState.Available(info)
            _state.value
        } catch (t: Throwable) {
            CrashlyticsHelper.log("AppUpdateManager: check failed - ${t.message}")
            CrashlyticsHelper.recordNonFatal(t)
            val err = AppUpdateState.Error("No se pudo comprobar actualizaciones: ${t.message ?: "error de red"}")
            _state.value = err
            err
        }
    }

    suspend fun downloadAndVerify(info: AppUpdateInfo): AppUpdateState = withContext(Dispatchers.IO) {
        val targetFile = File(updatesDir, "balddog-arcade-${info.versionName}.apk")
        val tmpFile = File(updatesDir, "balddog-arcade-${info.versionName}.apk.tmp")
        updatesDir.listFiles()?.forEach { existing ->
            if (existing.name != targetFile.name) existing.delete()
        }

        try {
            CrashlyticsHelper.log("AppUpdateManager: downloading ${info.tagName}")
            _state.value = AppUpdateState.Downloading(0f, 0L, info.sizeBytes)

            val apkReq = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "balddog-arcade-android/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(apkReq).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Descarga APK falló (HTTP ${response.code})")
                }
                val body = response.body ?: throw IllegalStateException("Cuerpo de descarga vacío")
                val total = body.contentLength().takeIf { it > 0L } ?: info.sizeBytes

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                            _state.value = AppUpdateState.Downloading(progress, downloaded, total)
                        }
                    }
                }
            }

            _state.value = AppUpdateState.Verifying
            val shaUrl = info.sha256Url
            if (shaUrl.isNullOrBlank()) {
                tmpFile.delete()
                throw SecurityException("La release no incluye archivo .sha256 para verificar integridad")
            }
            val shaReq = Request.Builder()
                .url(shaUrl)
                .header("User-Agent", "balddog-arcade-android/${BuildConfig.VERSION_NAME}")
                .build()
            val shaContent = client.newCall(shaReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Descarga SHA-256 falló (HTTP ${resp.code})")
                }
                resp.body?.string().orEmpty()
            }
            val expectedSha = parseSha256Content(shaContent)
                ?: throw IllegalStateException("Formato de archivo .sha256 inválido")
            if (!verifyApkSha256(tmpFile, expectedSha)) {
                tmpFile.delete()
                throw SecurityException("La verificación SHA-256 falló: el APK descargado está corrupto o alterado")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }

            CrashlyticsHelper.log("AppUpdateManager: APK verified and ready (${targetFile.name})")
            _state.value = AppUpdateState.ReadyToInstall(targetFile)
            _state.value
        } catch (t: Throwable) {
            tmpFile.delete()
            CrashlyticsHelper.log("AppUpdateManager: download/verify failed - ${t.message}")
            CrashlyticsHelper.recordNonFatal(t)
            val err = AppUpdateState.Error(t.message ?: "Error al descargar la actualización")
            _state.value = err
            err
        }
    }

    fun installApk(activityContext: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            _state.value = AppUpdateState.Error("El archivo APK ya no existe en caché")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activityContext.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activityContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activityContext.startActivity(permIntent)
            return false
        }

        val contentUri = FileProvider.getUriForFile(
            activityContext,
            "${activityContext.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activityContext.startActivity(installIntent)
        return true
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/balddogarcade/Balddog-Android-App/releases/latest"
        private const val APK_ASSET_NAME = "app-release.apk"
        private const val SHA256_ASSET_NAME = "app-release.apk.sha256"

        fun parseSemVer(raw: String): Triple<Int, Int, Int>? {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
            val parts = cleaned.split('.')
            if (parts.size < 2 || parts.size > 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (major < 0 || minor < 0 || patch < 0) return null
            return Triple(major, minor, patch)
        }

        fun isNewerVersion(remote: String, current: String): Boolean {
            val r = parseSemVer(remote) ?: return false
            val c = parseSemVer(current) ?: return false
            if (r.first != c.first) return r.first > c.first
            if (r.second != c.second) return r.second > c.second
            return r.third > c.third
        }

        fun parseSha256Content(content: String): String? {
            val token = content.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
            return if (token.matches(Regex("^[0-9a-f]{64}$"))) token else null
        }

        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun verifyApkSha256(file: File, expectedSha256: String): Boolean {
            if (!file.exists() || !file.isFile) return false
            val normalized = expectedSha256.trim().lowercase()
            if (!normalized.matches(Regex("^[0-9a-f]{64}$"))) return false
            return computeFileSha256(file) == normalized
        }
    }
}
