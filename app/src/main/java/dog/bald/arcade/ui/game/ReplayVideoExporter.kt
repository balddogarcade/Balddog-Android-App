package dog.bald.arcade.ui.game

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.balddog_android.openOfflineReplayRenderer
import java.io.File
import kotlin.math.roundToInt

/**
 * Exporta un replay determinista `.bsv.gz` a un archivo `.mp4` (H.264/AVC)
 * usando las APIs nativas estándar de Android (`MediaCodec` + `MediaMuxer`)
 * alimentadas en modo offline desde `OfflineReplayRenderer`.
 */
object ReplayVideoExporter {
    suspend fun exportToGallery(
        context: Context,
        nativeLibDir: String,
        romPath: String,
        systemDir: String,
        saveDir: String,
        replayPath: String,
        gameTitle: String,
        onProgress: (Int) -> Unit,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val outTmp = File(context.cacheDir, "clips/export_${System.currentTimeMillis()}.mp4")
            outTmp.parentFile?.mkdirs()

            var encoder: MediaCodec? = null
            var encoderStarted = false
            var muxer: MediaMuxer? = null
            var muxerStarted = false

            try {
                openOfflineReplayRenderer(
                    nativeLibDir = nativeLibDir,
                    romPath = romPath,
                    systemDir = systemDir,
                    saveDir = saveDir,
                    replayPath = replayPath,
                ).use { renderer ->
                    val meta = renderer.metadata()
                    val totalFrames = meta.totalFrames.toLong().coerceAtLeast(1L)
                    val fps = meta.fps.roundToInt().coerceIn(30, 60)

                    val firstFrame = renderer.stepFrame() ?: error("El replay no contiene frames")
                    val encWidth = (firstFrame.width.toInt().coerceAtLeast(16) + 1) and 1.inv()
                    val encHeight = (firstFrame.height.toInt().coerceAtLeast(16) + 1) and 1.inv()

                    val format =
                        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        }

                    val codec =
                        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                            encoder = it
                            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            it.start()
                            encoderStarted = true
                        }
                    val mediaMuxer =
                        MediaMuxer(outTmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                            muxer = it
                        }

                    val bufferInfo = MediaCodec.BufferInfo()
                    var trackIndex = -1
                    var pendingFrame: uniffi.balddog_android.OfflineVideoFrame? = firstFrame
                    var inputDone = false
                    var outputDone = false
                    val frameDurationUs = 1_000_000L / fps

                    while (!outputDone && coroutineContext.isActive) {
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val f = pendingFrame ?: renderer.stepFrame()
                                pendingFrame = null
                                if (f == null) {
                                    codec.queueInputBuffer(
                                        inIdx,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    val img = codec.getInputImage(inIdx)
                                    var queued = false
                                    try {
                                        if (img != null) {
                                            writeRgbaToYuvImage(f.rgba, f.width.toInt(), f.height.toInt(), img, encWidth, encHeight)
                                        }
                                        val ptsUs = (f.frameIndex.toLong() - 1L).coerceAtLeast(0L) * frameDurationUs
                                        val approxSize = (encWidth * encHeight * 3) / 2
                                        codec.queueInputBuffer(inIdx, 0, approxSize, ptsUs, 0)
                                        queued = true
                                    } finally {
                                        if (!queued && img != null) {
                                            runCatching { img.close() }
                                        }
                                    }
                                    val pct =
                                        ((f.frameIndex.toDouble() / totalFrames.toDouble()) * 100.0)
                                            .roundToInt()
                                            .coerceIn(0, 99)
                                    onProgress(pct)
                                }
                            }
                        }

                        val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    trackIndex = mediaMuxer.addTrack(codec.outputFormat)
                                    mediaMuxer.start()
                                    muxerStarted = true
                                }
                            }
                            outIdx >= 0 -> {
                                val outBuf = codec.getOutputBuffer(outIdx)
                                if (outBuf != null &&
                                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                    bufferInfo.size > 0
                                ) {
                                    if (muxerStarted) {
                                        outBuf.position(bufferInfo.offset)
                                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                        mediaMuxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                                    }
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }

                    if (!coroutineContext.isActive) {
                        throw CancellationException("Exportación cancelada")
                    }

                    onProgress(100)
                    if (muxerStarted) {
                        muxerStarted = false
                        mediaMuxer.stop()
                    }
                    mediaMuxer.release()
                    muxer = null
                    if (encoderStarted) {
                        encoderStarted = false
                        codec.stop()
                    }
                    codec.release()
                    encoder = null

                    if (!coroutineContext.isActive) {
                        throw CancellationException("Exportación cancelada")
                    }

                    val savedName = MediaExportHelper.saveMp4ToGallery(context, outTmp, gameTitle).getOrThrow()
                    Result.success(savedName)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                if (muxerStarted) {
                    muxerStarted = false
                    runCatching { muxer?.stop() }
                }
                runCatching { muxer?.release() }
                if (encoderStarted) {
                    encoderStarted = false
                    runCatching { encoder?.stop() }
                }
                runCatching { encoder?.release() }
                runCatching { outTmp.delete() }
            }
        }

    private fun writeRgbaToYuvImage(
        rgba: ByteArray,
        srcW: Int,
        srcH: Int,
        image: Image,
        dstW: Int,
        dstH: Int,
    ) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        val copyW = minOf(srcW, dstW)
        val copyH = minOf(srcH, dstH)

        for (y in 0 until copyH) {
            val srcRow = y * srcW * 4
            val yRowBase = y * yRowStride
            val uvRowBaseU = (y / 2) * uRowStride
            val uvRowBaseV = (y / 2) * vRowStride

            for (x in 0 until copyW) {
                val p = srcRow + x * 4
                val r = rgba[p].toInt() and 0xFF
                val g = rgba[p + 1].toInt() and 0xFF
                val b = rgba[p + 2].toInt() and 0xFF

                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(yRowBase + x * yPixStride, yVal.coerceIn(0, 255).toByte())

                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uvX = x / 2
                    uBuf.put(uvRowBaseU + uvX * uPixStride, uVal.coerceIn(0, 255).toByte())
                    vBuf.put(uvRowBaseV + uvX * vPixStride, vVal.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
