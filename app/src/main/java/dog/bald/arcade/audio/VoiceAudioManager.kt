package dog.bald.arcade.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class VoiceAudioManager(
    private val context: Context,
    private val onCapturePcm: (ShortArray) -> Unit,
    private val onPullPlaybackPcm: (Int) -> ShortArray
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms @ 16 kHz
    }

    private val running = AtomicBoolean(true)
    private val captureWanted = AtomicBoolean(false)
    private val playbackWanted = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    private val captureLock = Any()
    private val playbackLock = Any()

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setCaptureEnabled(enabled: Boolean) {
        captureWanted.set(enabled)
        if (enabled && !paused.get()) {
            ensureCaptureRunning()
        }
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        playbackWanted.set(enabled)
        if (enabled) {
            ensurePlaybackRunning()
        }
    }

    fun setPaused(isPaused: Boolean) {
        paused.set(isPaused)
        if (!isPaused && captureWanted.get()) {
            ensureCaptureRunning()
        }
    }

    private fun ensureCaptureRunning() {
        synchronized(captureLock) {
            if (!running.get() || captureThread?.isAlive == true) return
            if (!hasRecordPermission()) return
            captureThread = Thread({ runCaptureLoop() }, "balddog-voice-cap").apply {
                start()
            }
        }
    }

    private fun ensurePlaybackRunning() {
        synchronized(playbackLock) {
            if (!running.get() || playbackThread?.isAlive == true) return
            playbackThread = Thread({ runPlaybackLoop() }, "balddog-voice-play").apply {
                start()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2 * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes
            )
        } catch (_: Throwable) {
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return
        }

        val aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null

        val ns = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null

        val frameBuf = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            while (running.get() && captureWanted.get() && !paused.get()) {
                var offset = 0
                while (offset < FRAME_SAMPLES && running.get() && captureWanted.get() && !paused.get()) {
                    val read = record.read(frameBuf, offset, FRAME_SAMPLES - offset, AudioRecord.READ_BLOCKING)
                    if (read <= 0) {
                        Thread.sleep(5)
                        break
                    }
                    offset += read
                }
                if (offset == FRAME_SAMPLES) {
                    onCapturePcm(frameBuf.copyOf())
                }
            }
        } catch (_: Throwable) {
            // Ignorar interrupciones limpias al cerrar
        } finally {
            runCatching { record.stop() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { record.release() }
        }
    }

    private fun runPlaybackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2 * 4)

        // USAGE_MEDIA + CONTENT_TYPE_SPEECH mantiene A2DP estéreo en Bluetooth sin forzar HFP mono
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Throwable) {
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return
        }

        try {
            track.play()
            while (running.get() && playbackWanted.get()) {
                val pcm = onPullPlaybackPcm(FRAME_SAMPLES)
                if (pcm.isNotEmpty()) {
                    track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    Thread.sleep(15)
                }
            }
        } catch (_: Throwable) {
            // Ignorar al detener
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    fun release() {
        running.set(false)
        captureWanted.set(false)
        playbackWanted.set(false)
        runCatching { captureThread?.join(300) }
        runCatching { playbackThread?.join(300) }
        captureThread = null
        playbackThread = null
    }
}
