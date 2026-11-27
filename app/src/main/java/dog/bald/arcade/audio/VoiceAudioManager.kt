package dog.bald.arcade.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class VoiceAudioManager internal constructor(
    private val onCapturePcm: (ShortArray) -> Unit,
    private val onPullPlaybackPcm: (Int) -> ShortArray,
    private val backend: AudioBackend,
) {
    constructor(
        context: Context,
        onCapturePcm: (ShortArray) -> Unit,
        onPullPlaybackPcm: (Int) -> ShortArray,
    ) : this(onCapturePcm, onPullPlaybackPcm, AndroidAudioBackend(context))

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms @ 16 kHz
        private const val RELEASE_JOIN_TIMEOUT_MS = 1000L
    }

    internal interface CaptureDevice {
        fun startRecording()

        fun readBlocking(
            buffer: ShortArray,
            offset: Int,
            count: Int,
        ): Int

        fun stop()

        fun release()
    }

    internal interface PlaybackDevice {
        fun play()

        fun writeBlocking(
            buffer: ShortArray,
            offset: Int,
            count: Int,
        ): Int

        fun stop()

        fun release()
    }

    internal interface AudioBackend {
        fun hasRecordPermission(): Boolean

        fun openCapture(): CaptureDevice?

        fun openPlayback(): PlaybackDevice?
    }

    private class AndroidAudioBackend(
        private val context: Context,
    ) : AudioBackend {
        override fun hasRecordPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        @SuppressLint("MissingPermission")
        override fun openCapture(): CaptureDevice? {
            val minBufBytes =
                AudioRecord
                    .getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ).coerceAtLeast(FRAME_SAMPLES * 2 * 4)

            val record =
                try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufBytes,
                    )
                } catch (t: Throwable) {
                    dog.bald.arcade.diagnostics.CrashlyticsHelper.logNetError("voice_audio_capture", t)
                    return null
                }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                return null
            }

            val aec =
                if (AcousticEchoCanceler.isAvailable()) {
                    runCatching {
                        AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                } else {
                    null
                }

            val ns =
                if (NoiseSuppressor.isAvailable()) {
                    runCatching {
                        NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                } else {
                    null
                }

            return object : CaptureDevice {
                override fun startRecording() {
                    record.startRecording()
                }

                override fun readBlocking(
                    buffer: ShortArray,
                    offset: Int,
                    count: Int,
                ): Int = record.read(buffer, offset, count, AudioRecord.READ_BLOCKING)

                override fun stop() {
                    runCatching { record.stop() }
                }

                override fun release() {
                    runCatching { record.stop() }
                    runCatching { aec?.release() }
                    runCatching { ns?.release() }
                    runCatching { record.release() }
                }
            }
        }

        override fun openPlayback(): PlaybackDevice? {
            val minBufBytes =
                AudioTrack
                    .getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ).coerceAtLeast(FRAME_SAMPLES * 2 * 4)

            // USAGE_MEDIA + CONTENT_TYPE_SPEECH mantiene A2DP estéreo en Bluetooth sin forzar HFP mono
            val attrs =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            val format =
                AudioFormat
                    .Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

            val track =
                try {
                    AudioTrack
                        .Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minBufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()
                } catch (t: Throwable) {
                    dog.bald.arcade.diagnostics.CrashlyticsHelper.logNetError("voice_audio_capture", t)
                    return null
                }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return null
            }

            return object : PlaybackDevice {
                override fun play() {
                    track.play()
                }

                override fun writeBlocking(
                    buffer: ShortArray,
                    offset: Int,
                    count: Int,
                ): Int = track.write(buffer, offset, count, AudioTrack.WRITE_BLOCKING)

                override fun stop() {
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.stop() }
                }

                override fun release() {
                    stop()
                    runCatching { track.release() }
                }
            }
        }
    }

    private val running = AtomicBoolean(true)
    private val captureWanted = AtomicBoolean(false)
    private val playbackWanted = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    private val captureLock = Any()
    private val playbackLock = Any()

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    @Volatile
    private var activeCapture: CaptureDevice? = null

    @Volatile
    private var activePlayback: PlaybackDevice? = null

    fun hasRecordPermission(): Boolean = backend.hasRecordPermission()

    fun setCaptureEnabled(enabled: Boolean) {
        synchronized(captureLock) {
            captureWanted.set(enabled)
            if (enabled && running.get() && !paused.get()) {
                ensureCaptureRunningLocked()
            } else if (!enabled) {
                wakeCaptureLocked()
            }
        }
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        synchronized(playbackLock) {
            playbackWanted.set(enabled)
            if (enabled && running.get() && !paused.get()) {
                ensurePlaybackRunningLocked()
            } else if (!enabled) {
                wakePlaybackLocked()
            }
        }
    }

    fun setPaused(isPaused: Boolean) {
        paused.set(isPaused)
        synchronized(captureLock) {
            if (paused.get()) {
                wakeCaptureLocked()
            } else if (running.get() && captureWanted.get()) {
                ensureCaptureRunningLocked()
            }
        }
        synchronized(playbackLock) {
            if (paused.get()) {
                wakePlaybackLocked()
            } else if (running.get() && playbackWanted.get()) {
                ensurePlaybackRunningLocked()
            }
        }
    }

    private fun ensureCaptureRunningLocked() {
        if (!running.get() || !captureWanted.get() || paused.get() || captureThread?.isAlive == true) return
        if (!hasRecordPermission()) return
        captureThread =
            Thread({ runCaptureLoop() }, "balddog-voice-cap").apply {
                start()
            }
    }

    private fun ensurePlaybackRunningLocked() {
        if (!running.get() || !playbackWanted.get() || paused.get() || playbackThread?.isAlive == true) return
        playbackThread =
            Thread({ runPlaybackLoop() }, "balddog-voice-play").apply {
                start()
            }
    }

    private fun wakeCaptureLocked() {
        val dev = activeCapture
        activeCapture = null
        dev?.stop()
        captureThread?.interrupt()
    }

    private fun wakePlaybackLocked() {
        val dev = activePlayback
        activePlayback = null
        dev?.stop()
        playbackThread?.interrupt()
    }

    private fun runCaptureLoop() {
        var device: CaptureDevice? = null
        var canRestart = false
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val opened = backend.openCapture() ?: return
            val shouldRun =
                synchronized(captureLock) {
                    if (!running.get() || !captureWanted.get() || paused.get() || captureThread !== Thread.currentThread()) {
                        canRestart = true
                        false
                    } else {
                        Thread.interrupted()
                        device = opened
                        activeCapture = opened
                        true
                    }
                }
            if (!shouldRun) {
                runCatching { opened.release() }
                return
            }

            opened.startRecording()
            canRestart = true
            val frameBuf = ShortArray(FRAME_SAMPLES)
            while (running.get() && captureWanted.get() && !paused.get() && activeCapture === opened) {
                var offset = 0
                while (offset < FRAME_SAMPLES && running.get() && captureWanted.get() && !paused.get() && activeCapture === opened) {
                    val read = opened.readBlocking(frameBuf, offset, FRAME_SAMPLES - offset)
                    if (read <= 0) {
                        if (!running.get() || !captureWanted.get() || paused.get() || activeCapture !== opened) break
                        Thread.sleep(5)
                        break
                    }
                    offset += read
                }
                if (offset == FRAME_SAMPLES && running.get() && captureWanted.get() && !paused.get() && activeCapture === opened) {
                    onCapturePcm(frameBuf.copyOf())
                }
            }
        } catch (_: Throwable) {
            // Ignorar interrupciones limpias al cerrar
        } finally {
            Thread.interrupted()
            synchronized(captureLock) {
                if (device != null && activeCapture === device) {
                    activeCapture = null
                }
            }
            device?.let { d ->
                runCatching { d.release() }
            }
            synchronized(captureLock) {
                if (captureThread === Thread.currentThread()) {
                    captureThread = null
                }
                if (canRestart && running.get() && captureWanted.get() && !paused.get()) {
                    ensureCaptureRunningLocked()
                }
            }
        }
    }

    private fun runPlaybackLoop() {
        var device: PlaybackDevice? = null
        var canRestart = false
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val opened = backend.openPlayback() ?: return
            val shouldRun =
                synchronized(playbackLock) {
                    if (!running.get() || !playbackWanted.get() || paused.get() || playbackThread !== Thread.currentThread()) {
                        canRestart = true
                        false
                    } else {
                        Thread.interrupted()
                        device = opened
                        activePlayback = opened
                        true
                    }
                }
            if (!shouldRun) {
                runCatching { opened.release() }
                return
            }

            opened.play()
            canRestart = true
            while (running.get() && playbackWanted.get() && !paused.get() && activePlayback === opened) {
                val pcm = onPullPlaybackPcm(FRAME_SAMPLES)
                if (pcm.isNotEmpty()) {
                    val written = opened.writeBlocking(pcm, 0, pcm.size)
                    if (written <= 0) {
                        if (!running.get() || !playbackWanted.get() || paused.get() || activePlayback !== opened) break
                        Thread.sleep(10)
                    }
                } else {
                    if (!running.get() || !playbackWanted.get() || paused.get() || activePlayback !== opened) break
                    Thread.sleep(15)
                }
            }
        } catch (_: Throwable) {
            // Ignorar al detener
        } finally {
            Thread.interrupted()
            synchronized(playbackLock) {
                if (device != null && activePlayback === device) {
                    activePlayback = null
                }
            }
            device?.let { d ->
                runCatching { d.release() }
            }
            synchronized(playbackLock) {
                if (playbackThread === Thread.currentThread()) {
                    playbackThread = null
                }
                if (canRestart && running.get() && playbackWanted.get() && !paused.get()) {
                    ensurePlaybackRunningLocked()
                }
            }
        }
    }

    fun release() {
        running.set(false)
        captureWanted.set(false)
        playbackWanted.set(false)

        val capThread: Thread?
        synchronized(captureLock) {
            capThread = captureThread
            wakeCaptureLocked()
        }

        val playThread: Thread?
        synchronized(playbackLock) {
            playThread = playbackThread
            wakePlaybackLocked()
        }

        if (capThread != null && capThread !== Thread.currentThread()) {
            runCatching { capThread.join(RELEASE_JOIN_TIMEOUT_MS) }
        }
        if (playThread != null && playThread !== Thread.currentThread()) {
            runCatching { playThread.join(RELEASE_JOIN_TIMEOUT_MS) }
        }

        synchronized(captureLock) {
            if (captureThread === capThread && capThread?.isAlive != true) {
                captureThread = null
            }
            if (capThread?.isAlive != true) {
                activeCapture = null
            }
        }
        synchronized(playbackLock) {
            if (playbackThread === playThread && playThread?.isAlive != true) {
                playbackThread = null
            }
            if (playThread?.isAlive != true) {
                activePlayback = null
            }
        }
    }
}
