package dog.bald.arcade.data

import android.content.Context
import dog.bald.arcade.diagnostics.CrashlyticsHelper
import dog.bald.arcade.diagnostics.PerformanceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class RomIntegrityException(message: String) : Exception(message)

class IpfsRomManager(context: Context) {
    private val romsDir = File(context.filesDir, "roms").apply { mkdirs() }
    private val systemDir = File(context.filesDir, "system").apply { mkdirs() }
    private val savestatesDir = File(context.filesDir, "savestates").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val replaysCacheDir = File(context.cacheDir, "replays")

    private fun safeFile(dir: File, filename: String): File {
        val clean = File(filename).name
        require(clean.isNotBlank() && clean == filename && !clean.contains("..")) {
            "Nombre de archivo inválido: $filename"
        }
        return File(dir, clean)
    }

    private val gateways = listOf(
        "https://gateway.pinata.cloud/ipfs/",
        "https://ipfs.io/ipfs/",
        "https://dweb.link/ipfs/"
    )

    fun isGameDownloaded(game: GameDto): Boolean {
        val f = File(romsDir, game.romFilename)
        return f.exists() && f.length() > 0L
    }

    fun getRomFile(game: GameDto): File = File(romsDir, game.romFilename)

    fun listDownloadedFilenames(): Set<String> {
        return romsDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    fun totalCacheBytes(): Long {
        fun dirSize(dir: File): Long =
            dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        return dirSize(romsDir) + dirSize(systemDir) + dirSize(savestatesDir)
    }

    fun deleteRom(filename: String) {
        File(romsDir, filename).delete()
    }

    fun clearAllCache() {
        listOf(romsDir, systemDir, savestatesDir, replaysCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    fun verifyExistingFile(
        file: File,
        expectedSha256: String,
        expectedCrc32: String,
        expectedBytes: Long
    ): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (expectedBytes > 0L && file.length() != expectedBytes) return false
        return try {
            val sha = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            val buf = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    sha.update(buf, 0, read)
                    crc.update(buf, 0, read)
                }
            }
            val actualSha = sha.digest().joinToString("") { "%02x".format(it) }
            val actualCrc = "%08x".format(crc.value)
            val shaOk = expectedSha256.isBlank() || actualSha.equals(expectedSha256.trim(), ignoreCase = true)
            val crcOk = expectedCrc32.isBlank() || actualCrc.equals(expectedCrc32.trim(), ignoreCase = true)
            shaOk && crcOk
        } catch (t: Throwable) {
            CrashlyticsHelper.logIpfsError(file.name, "local_verify", t)
            false
        }
    }

    suspend fun ensureGameReady(
        game: GameDto,
        allGames: List<GameDto>,
        onProgress: (stage: String, progress: Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // 1. ROM Parent si es un clon
        if (!game.parentGameId.isNullOrBlank()) {
            val parent = allGames.firstOrNull { it.id == game.parentGameId }
            if (parent != null) {
                val parentFile = File(romsDir, parent.romFilename)
                val valid = parentFile.exists() && (parent.sizeBytes <= 0L || parentFile.length() == parent.sizeBytes)
                if (!valid) {
                    onProgress("Descargando ROM base ${parent.romFilename}...", 0f)
                    downloadAndVerify(
                        cid = parent.ipfsCid,
                        destFile = parentFile,
                        expectedSha256 = parent.sha256,
                        expectedCrc32 = parent.crc32,
                        expectedBytes = parent.sizeBytes
                    ) { p -> onProgress("Base ${parent.romFilename}", p) }
                }
            }
        }

        // 3. ROM principal
        val target = File(romsDir, game.romFilename)
        val valid = target.exists() && (game.sizeBytes <= 0L || target.length() == game.sizeBytes)
        if (!valid) {
            onProgress("Descargando ${game.title}...", 0f)
            downloadAndVerify(
                cid = game.ipfsCid,
                destFile = target,
                expectedSha256 = game.sha256,
                expectedCrc32 = game.crc32,
                expectedBytes = game.sizeBytes
            ) { p -> onProgress(game.title, p) }
        }

        target
    }

    private fun downloadAndVerify(
        cid: String,
        destFile: File,
        expectedSha256: String,
        expectedCrc32: String,
        expectedBytes: Long,
        onProgress: (Float) -> Unit
    ) {
        val tmpFile = File(destFile.parentFile, "${destFile.name}.${UUID.randomUUID()}.tmp")
        var lastError: Throwable? = null
        val trace = PerformanceHelper.startTrace("ipfs_rom_download_trace")
        var attempts = 0L
        var downloadedBytes = 0L
        var outcome = "error"

        try {
            for (gw in gateways) {
                attempts++
                tmpFile.delete()
                val url = "$gw$cid"
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RomIntegrityException("HTTP ${resp.code} en gateway IPFS")
                        }
                        val body = resp.body ?: throw RomIntegrityException("Respuesta vacía de IPFS")
                        val contentLen = body.contentLength().takeIf { it > 0 } ?: expectedBytes

                        val sha = MessageDigest.getInstance("SHA-256")
                        val crc = CRC32()
                        val buf = ByteArray(64 * 1024)
                        var totalRead = 0L

                        body.byteStream().use { input ->
                            FileOutputStream(tmpFile).use { out ->
                                while (true) {
                                    val read = input.read(buf)
                                    if (read <= 0) break
                                    out.write(buf, 0, read)
                                    sha.update(buf, 0, read)
                                    crc.update(buf, 0, read)
                                    totalRead += read
                                    if (contentLen > 0) {
                                        onProgress((totalRead.toFloat() / contentLen.toFloat()).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }

                        val actualSha256 = sha.digest().joinToString("") { "%02x".format(it) }
                        val actualCrc32 = "%08x".format(crc.value)

                        if (expectedSha256.isNotBlank() && !actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                            tmpFile.delete()
                            throw RomIntegrityException("SHA256 inválido para ${destFile.name}")
                        }
                        if (expectedCrc32.isNotBlank() && !actualCrc32.equals(expectedCrc32.trim(), ignoreCase = true)) {
                            tmpFile.delete()
                            throw RomIntegrityException("CRC32 inválido para ${destFile.name}")
                        }

                        if (destFile.exists()) destFile.delete()
                        if (!tmpFile.renameTo(destFile)) {
                            tmpFile.copyTo(destFile, overwrite = true)
                            tmpFile.delete()
                        }
                        downloadedBytes = totalRead
                        outcome = "success"
                        CrashlyticsHelper.logIpfsEvent(destFile.name, "download_ok", gw, "bytes=$totalRead")
                        onProgress(1f)
                        return
                    }
                } catch (t: Throwable) {
                    tmpFile.delete()
                    lastError = t
                    CrashlyticsHelper.logIpfsError(destFile.name, gw, t)
                }
            }

            throw lastError ?: RomIntegrityException("No se pudo descargar desde ningún gateway IPFS")
        } finally {
            PerformanceHelper.stopTrace(
                trace,
                attributes = mapOf("rom_file" to destFile.name, "outcome" to outcome),
                metrics = mapOf("bytes_downloaded" to downloadedBytes, "gateway_attempts" to attempts),
            )
        }
    }
}
