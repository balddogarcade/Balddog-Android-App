package dog.bald.arcade.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoiceAudioManagerTest {

    private class FakeCaptureDevice(
        private val activeCounter: AtomicInteger,
        private val maxActiveCounter: AtomicInteger,
        private val blockInReadUntilStop: Boolean = false,
        private val onReleaseStart: (() -> Unit)? = null,
        private val releaseGate: CountDownLatch? = null
    ) : VoiceAudioManager.CaptureDevice {
        val stopped = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val enteredRead = CountDownLatch(1)
        val releaseCompleted = CountDownLatch(1)
        private val stopLatch = CountDownLatch(1)

        init {
            val active = activeCounter.incrementAndGet()
            maxActiveCounter.updateAndGet { maxOf(it, active) }
        }

        override fun startRecording() = Unit

        override fun readBlocking(buffer: ShortArray, offset: Int, count: Int): Int {
            enteredRead.countDown()
            if (blockInReadUntilStop) {
                // Simulate native AudioRecord.READ_BLOCKING ignoring Thread.interrupt() until stop() is called.
                while (!stopped.get()) {
                    try {
                        stopLatch.await()
                    } catch (_: InterruptedException) {
                        // Keep blocking until stop() unblocks native I/O.
                    }
                }
                return 0
            }
            if (stopped.get()) return 0
            for (i in offset until offset + count) {
                buffer[i] = 42
            }
            return count
        }

        override fun stop() {
            stopped.set(true)
            stopLatch.countDown()
        }

        override fun release() {
            stop()
            onReleaseStart?.invoke()
            releaseGate?.await(5, TimeUnit.SECONDS)
            if (released.compareAndSet(false, true)) {
                activeCounter.decrementAndGet()
            }
            releaseCompleted.countDown()
        }
    }

    private class FakePlaybackDevice(
        private val activeCounter: AtomicInteger,
        private val maxActiveCounter: AtomicInteger,
        private val blockInWriteUntilStop: Boolean = false,
        private val onReleaseStart: (() -> Unit)? = null,
        private val releaseGate: CountDownLatch? = null
    ) : VoiceAudioManager.PlaybackDevice {
        val stopped = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val enteredWrite = CountDownLatch(1)
        val releaseCompleted = CountDownLatch(1)
        private val stopLatch = CountDownLatch(1)

        init {
            val active = activeCounter.incrementAndGet()
            maxActiveCounter.updateAndGet { maxOf(it, active) }
        }

        override fun play() = Unit

        override fun writeBlocking(buffer: ShortArray, offset: Int, count: Int): Int {
            enteredWrite.countDown()
            if (blockInWriteUntilStop) {
                // Simulate native AudioTrack.WRITE_BLOCKING ignoring Thread.interrupt() until stop() is called.
                while (!stopped.get()) {
                    try {
                        stopLatch.await()
                    } catch (_: InterruptedException) {
                        // Keep blocking until stop() unblocks native I/O.
                    }
                }
                return 0
            }
            return if (stopped.get()) 0 else count
        }

        override fun stop() {
            stopped.set(true)
            stopLatch.countDown()
        }

        override fun release() {
            stop()
            onReleaseStart?.invoke()
            releaseGate?.await(5, TimeUnit.SECONDS)
            if (released.compareAndSet(false, true)) {
                activeCounter.decrementAndGet()
            }
            releaseCompleted.countDown()
        }
    }

    private class FakeBackend(
        var recordPermission: Boolean = true,
        private val captureFactory: (Int, AtomicInteger, AtomicInteger) -> VoiceAudioManager.CaptureDevice?,
        private val playbackFactory: (Int, AtomicInteger, AtomicInteger) -> VoiceAudioManager.PlaybackDevice?
    ) : VoiceAudioManager.AudioBackend {
        val openCaptureCount = AtomicInteger(0)
        val openPlaybackCount = AtomicInteger(0)
        val activeCaptureCount = AtomicInteger(0)
        val activePlaybackCount = AtomicInteger(0)
        val maxActiveCaptureCount = AtomicInteger(0)
        val maxActivePlaybackCount = AtomicInteger(0)

        override fun hasRecordPermission(): Boolean = recordPermission

        override fun openCapture(): VoiceAudioManager.CaptureDevice? {
            val idx = openCaptureCount.incrementAndGet()
            return captureFactory(idx, activeCaptureCount, maxActiveCaptureCount)
        }

        override fun openPlayback(): VoiceAudioManager.PlaybackDevice? {
            val idx = openPlaybackCount.incrementAndGet()
            return playbackFactory(idx, activePlaybackCount, maxActivePlaybackCount)
        }
    }

    @Test
    fun pausedStopsPlaybackAndResumesBothCaptureAndPlaybackOnUnpause() {
        val firstCapture = mutableListOf<FakeCaptureDevice>()
        val firstPlayback = mutableListOf<FakePlaybackDevice>()
        val firstCaptureCreated = CountDownLatch(1)
        val firstPlaybackCreated = CountDownLatch(1)
        val secondCaptureActive = CountDownLatch(1)
        val secondPlaybackActive = CountDownLatch(1)
        val pulledFirst = CountDownLatch(1)
        val capturedSecond = CountDownLatch(1)
        val pulledSecond = CountDownLatch(1)
        val phase = AtomicInteger(1)

        val backend = FakeBackend(
            captureFactory = { idx, active, maxActive ->
                val dev = FakeCaptureDevice(active, maxActive, blockInReadUntilStop = (idx == 1))
                if (idx == 1) {
                    firstCapture += dev
                    firstCaptureCreated.countDown()
                } else {
                    secondCaptureActive.countDown()
                }
                dev
            },
            playbackFactory = { idx, active, maxActive ->
                val dev = FakePlaybackDevice(active, maxActive, blockInWriteUntilStop = (idx == 1))
                if (idx == 1) {
                    firstPlayback += dev
                    firstPlaybackCreated.countDown()
                } else {
                    secondPlaybackActive.countDown()
                }
                dev
            }
        )

        val manager = VoiceAudioManager(
            onCapturePcm = {
                if (phase.get() == 2) capturedSecond.countDown()
            },
            onPullPlaybackPcm = { size ->
                if (phase.get() == 1) pulledFirst.countDown() else pulledSecond.countDown()
                ShortArray(size) { 7 }
            },
            backend = backend
        )

        try {
            manager.setCaptureEnabled(true)
            manager.setPlaybackEnabled(true)

            assertTrue("First capture device should be opened", firstCaptureCreated.await(5, TimeUnit.SECONDS))
            assertTrue("First capture device should enter read", firstCapture.single().enteredRead.await(5, TimeUnit.SECONDS))
            assertTrue("First playback should pull PCM", pulledFirst.await(5, TimeUnit.SECONDS))
            assertTrue("First playback device should be opened", firstPlaybackCreated.await(5, TimeUnit.SECONDS))
            assertTrue("First playback device should enter write", firstPlayback.single().enteredWrite.await(5, TimeUnit.SECONDS))

            phase.set(2)
            manager.setPaused(true)

            assertTrue("Capture device must be stopped and released on pause", firstCapture.single().releaseCompleted.await(5, TimeUnit.SECONDS))
            assertTrue("Playback device must be stopped and released on pause", firstPlayback.single().releaseCompleted.await(5, TimeUnit.SECONDS))
            assertEquals(0, backend.activeCaptureCount.get())
            assertEquals(0, backend.activePlaybackCount.get())

            manager.setPaused(false)

            assertTrue("Capture should restart on unpause", secondCaptureActive.await(5, TimeUnit.SECONDS))
            assertTrue("Playback should restart on unpause", secondPlaybackActive.await(5, TimeUnit.SECONDS))
            assertTrue("Capture should emit frames after unpause", capturedSecond.await(5, TimeUnit.SECONDS))
            assertTrue("Playback should pull PCM after unpause", pulledSecond.await(5, TimeUnit.SECONDS))
        } finally {
            manager.release()
        }
    }

    @Test
    fun setEnabledWhilePausedDoesNotStartUntilUnpaused() {
        val captureStarted = CountDownLatch(1)
        val playbackStarted = CountDownLatch(1)

        val backend = FakeBackend(
            captureFactory = { _, active, maxActive ->
                captureStarted.countDown()
                FakeCaptureDevice(active, maxActive)
            },
            playbackFactory = { _, active, maxActive ->
                playbackStarted.countDown()
                FakePlaybackDevice(active, maxActive)
            }
        )

        val manager = VoiceAudioManager(
            onCapturePcm = {},
            onPullPlaybackPcm = { ShortArray(it) },
            backend = backend
        )

        try {
            manager.setPaused(true)
            manager.setCaptureEnabled(true)
            manager.setPlaybackEnabled(true)

            Thread.sleep(50)
            assertEquals(0, backend.openCaptureCount.get())
            assertEquals(0, backend.openPlaybackCount.get())

            manager.setPaused(false)

            assertTrue("Capture should start once unpaused", captureStarted.await(5, TimeUnit.SECONDS))
            assertTrue("Playback should start once unpaused", playbackStarted.await(5, TimeUnit.SECONDS))
        } finally {
            manager.release()
        }
    }

    @Test
    fun rapidToggleCaptureDuringSlowReleaseNeverStrandsThreadOrOverlapsDevices() {
        val dev1ReleaseStarted = CountDownLatch(1)
        val allowDev1Release = CountDownLatch(1)
        val dev1EnteredRead = CountDownLatch(1)
        val dev2EmittedFrame = CountDownLatch(1)
        val currentOpenIndex = AtomicInteger(0)

        val backend = FakeBackend(
            captureFactory = { idx, active, maxActive ->
                currentOpenIndex.set(idx)
                if (idx == 1) {
                    FakeCaptureDevice(
                        activeCounter = active,
                        maxActiveCounter = maxActive,
                        blockInReadUntilStop = true,
                        onReleaseStart = { dev1ReleaseStarted.countDown() },
                        releaseGate = allowDev1Release
                    ).also { dev ->
                        Thread {
                            dev.enteredRead.await(5, TimeUnit.SECONDS)
                            dev1EnteredRead.countDown()
                        }.start()
                    }
                } else {
                    FakeCaptureDevice(active, maxActive)
                }
            },
            playbackFactory = { _, active, maxActive -> FakePlaybackDevice(active, maxActive) }
        )

        val manager = VoiceAudioManager(
            onCapturePcm = {
                if (currentOpenIndex.get() >= 2) {
                    dev2EmittedFrame.countDown()
                }
            },
            onPullPlaybackPcm = { ShortArray(0) },
            backend = backend
        )

        try {
            manager.setCaptureEnabled(true)
            assertTrue("Device 1 should enter read", dev1EnteredRead.await(5, TimeUnit.SECONDS))

            manager.setCaptureEnabled(false)
            assertTrue("Device 1 should enter slow release()", dev1ReleaseStarted.await(5, TimeUnit.SECONDS))

            // Toggle back on while thread 1 is still inside native release()
            manager.setCaptureEnabled(true)
            assertEquals("Must not open second device while first is releasing", 1, backend.openCaptureCount.get())

            allowDev1Release.countDown()
            assertTrue("Second capture device must start and deliver frames after slow release", dev2EmittedFrame.await(5, TimeUnit.SECONDS))
            assertEquals(2, backend.openCaptureCount.get())
            assertEquals(1, backend.maxActiveCaptureCount.get())
        } finally {
            allowDev1Release.countDown()
            manager.release()
        }
    }

    @Test
    fun rapidTogglePlaybackDuringSlowReleaseNeverStrandsThreadOrOverlapsDevices() {
        val dev1ReleaseStarted = CountDownLatch(1)
        val allowDev1Release = CountDownLatch(1)
        val dev1EnteredWrite = CountDownLatch(1)
        val dev2PulledAudio = CountDownLatch(1)
        val currentOpenIndex = AtomicInteger(0)

        val backend = FakeBackend(
            captureFactory = { _, active, maxActive -> FakeCaptureDevice(active, maxActive) },
            playbackFactory = { idx, active, maxActive ->
                currentOpenIndex.set(idx)
                if (idx == 1) {
                    FakePlaybackDevice(
                        activeCounter = active,
                        maxActiveCounter = maxActive,
                        blockInWriteUntilStop = true,
                        onReleaseStart = { dev1ReleaseStarted.countDown() },
                        releaseGate = allowDev1Release
                    ).also { dev ->
                        Thread {
                            dev.enteredWrite.await(5, TimeUnit.SECONDS)
                            dev1EnteredWrite.countDown()
                        }.start()
                    }
                } else {
                    FakePlaybackDevice(active, maxActive)
                }
            }
        )

        val manager = VoiceAudioManager(
            onCapturePcm = {},
            onPullPlaybackPcm = { size ->
                if (currentOpenIndex.get() >= 2) {
                    dev2PulledAudio.countDown()
                }
                ShortArray(size) { 3 }
            },
            backend = backend
        )

        try {
            manager.setPlaybackEnabled(true)
            assertTrue("Device 1 should enter write", dev1EnteredWrite.await(5, TimeUnit.SECONDS))

            manager.setPlaybackEnabled(false)
            assertTrue("Device 1 should enter slow release()", dev1ReleaseStarted.await(5, TimeUnit.SECONDS))

            manager.setPlaybackEnabled(true)
            assertEquals("Must not open second playback device while first is releasing", 1, backend.openPlaybackCount.get())

            allowDev1Release.countDown()
            assertTrue("Second playback device must start and pull PCM after slow release", dev2PulledAudio.await(5, TimeUnit.SECONDS))
            assertEquals(2, backend.openPlaybackCount.get())
            assertEquals(1, backend.maxActivePlaybackCount.get())
        } finally {
            allowDev1Release.countDown()
            manager.release()
        }
    }

    @Test
    fun releaseUnblocksBlockingIoImmediatelyAndPreventsRestart() {
        val capCreated = CountDownLatch(1)
        val playCreated = CountDownLatch(1)
        lateinit var capDev: FakeCaptureDevice
        lateinit var playDev: FakePlaybackDevice

        val backend = FakeBackend(
            captureFactory = { _, active, maxActive ->
                FakeCaptureDevice(active, maxActive, blockInReadUntilStop = true).also {
                    capDev = it
                    capCreated.countDown()
                }
            },
            playbackFactory = { _, active, maxActive ->
                FakePlaybackDevice(active, maxActive, blockInWriteUntilStop = true).also {
                    playDev = it
                    playCreated.countDown()
                }
            }
        )

        val manager = VoiceAudioManager(
            onCapturePcm = {},
            onPullPlaybackPcm = { ShortArray(it) { 1 } },
            backend = backend
        )

        manager.setCaptureEnabled(true)
        manager.setPlaybackEnabled(true)

        assertTrue(capCreated.await(5, TimeUnit.SECONDS))
        assertTrue(playCreated.await(5, TimeUnit.SECONDS))
        assertTrue(capDev.enteredRead.await(5, TimeUnit.SECONDS))
        assertTrue(playDev.enteredWrite.await(5, TimeUnit.SECONDS))

        val startMs = System.nanoTime()
        manager.release()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs)

        assertTrue("release() should unblock immediately instead of hanging (took ${elapsedMs}ms)", elapsedMs < 800)
        assertTrue(capDev.released.get())
        assertTrue(playDev.released.get())
        assertEquals(0, backend.activeCaptureCount.get())
        assertEquals(0, backend.activePlaybackCount.get())

        manager.setCaptureEnabled(true)
        manager.setPlaybackEnabled(true)
        manager.setPaused(false)
        Thread.sleep(30)
        assertEquals(1, backend.openCaptureCount.get())
        assertEquals(1, backend.openPlaybackCount.get())
    }

    @Test
    fun rapidPauseAndUnpauseWhileBlockedReplacesStoppedDevices() {
        lateinit var capDev1: FakeCaptureDevice
        lateinit var playDev1: FakePlaybackDevice
        val dev1Ready = CountDownLatch(2)
        val dev2CaptureEmitted = CountDownLatch(1)
        val dev2PlaybackPulled = CountDownLatch(1)
        val capIdx = AtomicInteger(0)
        val playIdx = AtomicInteger(0)

        val backend = FakeBackend(
            captureFactory = { idx, active, maxActive ->
                capIdx.set(idx)
                FakeCaptureDevice(active, maxActive, blockInReadUntilStop = (idx == 1)).also {
                    if (idx == 1) {
                        capDev1 = it
                        dev1Ready.countDown()
                    }
                }
            },
            playbackFactory = { idx, active, maxActive ->
                playIdx.set(idx)
                FakePlaybackDevice(active, maxActive, blockInWriteUntilStop = (idx == 1)).also {
                    if (idx == 1) {
                        playDev1 = it
                        dev1Ready.countDown()
                    }
                }
            }
        )

        val manager = VoiceAudioManager(
            onCapturePcm = {
                if (capIdx.get() >= 2) dev2CaptureEmitted.countDown()
            },
            onPullPlaybackPcm = { size ->
                if (playIdx.get() >= 2) dev2PlaybackPulled.countDown()
                ShortArray(size) { 5 }
            },
            backend = backend
        )

        try {
            manager.setCaptureEnabled(true)
            manager.setPlaybackEnabled(true)

            assertTrue(dev1Ready.await(5, TimeUnit.SECONDS))
            assertTrue(capDev1.enteredRead.await(5, TimeUnit.SECONDS))
            assertTrue(playDev1.enteredWrite.await(5, TimeUnit.SECONDS))

            // Pause then immediately unpause before worker threads leave readBlocking/writeBlocking
            manager.setPaused(true)
            manager.setPaused(false)

            assertTrue("Stopped capture device 1 must be released", capDev1.releaseCompleted.await(5, TimeUnit.SECONDS))
            assertTrue("Stopped playback device 1 must be released", playDev1.releaseCompleted.await(5, TimeUnit.SECONDS))
            assertTrue("Fresh capture device 2 must open and deliver PCM", dev2CaptureEmitted.await(5, TimeUnit.SECONDS))
            assertTrue("Fresh playback device 2 must open and pull PCM", dev2PlaybackPulled.await(5, TimeUnit.SECONDS))
            assertEquals(1, backend.maxActiveCaptureCount.get())
            assertEquals(1, backend.maxActivePlaybackCount.get())
        } finally {
            manager.release()
        }
    }

    @Test
    fun capturePermissionDeniedSkipsCaptureWithoutAffectingPlayback() {
        val playbackStarted = CountDownLatch(1)
        val backend = FakeBackend(
            recordPermission = false,
            captureFactory = { _, active, maxActive -> FakeCaptureDevice(active, maxActive) },
            playbackFactory = { _, active, maxActive ->
                playbackStarted.countDown()
                FakePlaybackDevice(active, maxActive)
            }
        )

        val manager = VoiceAudioManager(
            onCapturePcm = {},
            onPullPlaybackPcm = { ShortArray(it) },
            backend = backend
        )

        try {
            manager.setCaptureEnabled(true)
            manager.setPlaybackEnabled(true)
            assertTrue(playbackStarted.await(5, TimeUnit.SECONDS))
            assertEquals(0, backend.openCaptureCount.get())
        } finally {
            manager.release()
        }
    }
}
