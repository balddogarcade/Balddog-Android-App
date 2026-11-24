package dog.bald.arcade

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionPolicyTest {

    @Test
    fun transientCanDuck_ducksWithoutPausingInAllModes() {
        val modes = listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, true, true)
        )
        for ((isNetplay, isReplay, manuallyPaused) in modes) {
            val action = GameSessionPolicy.resolveAudioFocusChange(
                focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                isNetplay = isNetplay,
                showMenu = false,
                isReplayMode = isReplay,
                replayManuallyPaused = manuallyPaused,
                voiceListenEnabled = false,
                remoteSpeakingMask = 0
            )
            assertEquals(true, action.audioFocusDucked)
            assertEquals(true, action.setDucking)
            assertNull(action.setPaused)
        }
    }

    @Test
    fun audioFocusLoss_pausesSoloAndReplayButDucksNetplay() {
        val lossTypes = listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        )
        for (loss in lossTypes) {
            val soloAction = GameSessionPolicy.resolveAudioFocusChange(
                focusChange = loss,
                isNetplay = false,
                showMenu = false,
                isReplayMode = false,
                replayManuallyPaused = false,
                voiceListenEnabled = false,
                remoteSpeakingMask = 0
            )
            assertEquals(false, soloAction.audioFocusDucked)
            assertEquals(false, soloAction.setDucking)
            assertEquals(true, soloAction.setPaused)

            val replayAction = GameSessionPolicy.resolveAudioFocusChange(
                focusChange = loss,
                isNetplay = false,
                showMenu = false,
                isReplayMode = true,
                replayManuallyPaused = false,
                voiceListenEnabled = false,
                remoteSpeakingMask = 0
            )
            assertEquals(false, replayAction.audioFocusDucked)
            assertEquals(false, replayAction.setDucking)
            assertEquals(true, replayAction.setPaused)

            val netplayAction = GameSessionPolicy.resolveAudioFocusChange(
                focusChange = loss,
                isNetplay = true,
                showMenu = false,
                isReplayMode = false,
                replayManuallyPaused = false,
                voiceListenEnabled = true,
                remoteSpeakingMask = 0b10
            )
            assertEquals(true, netplayAction.audioFocusDucked)
            assertEquals(true, netplayAction.setDucking)
            assertNull(netplayAction.setPaused)
        }
    }

    @Test
    fun audioFocusGain_preservesManualReplayPauseAndMenuPauseAndRestoresVoiceDucking() {
        val replayPausedAction = GameSessionPolicy.resolveAudioFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isNetplay = false,
            showMenu = false,
            isReplayMode = true,
            replayManuallyPaused = true,
            voiceListenEnabled = false,
            remoteSpeakingMask = 0
        )
        assertEquals(false, replayPausedAction.audioFocusDucked)
        assertEquals(false, replayPausedAction.setDucking)
        assertNull(replayPausedAction.setPaused)

        val menuOpenAction = GameSessionPolicy.resolveAudioFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isNetplay = false,
            showMenu = true,
            isReplayMode = false,
            replayManuallyPaused = false,
            voiceListenEnabled = false,
            remoteSpeakingMask = 0
        )
        assertEquals(false, menuOpenAction.audioFocusDucked)
        assertEquals(false, menuOpenAction.setDucking)
        assertNull(menuOpenAction.setPaused)

        val netplaySpeakingAction = GameSessionPolicy.resolveAudioFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isNetplay = true,
            showMenu = false,
            isReplayMode = false,
            replayManuallyPaused = false,
            voiceListenEnabled = true,
            remoteSpeakingMask = 0b01
        )
        assertEquals(false, netplaySpeakingAction.audioFocusDucked)
        assertEquals(true, netplaySpeakingAction.setDucking)
        assertEquals(false, netplaySpeakingAction.setPaused)

        val replayPlayingAction = GameSessionPolicy.resolveAudioFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isNetplay = false,
            showMenu = false,
            isReplayMode = true,
            replayManuallyPaused = false,
            voiceListenEnabled = false,
            remoteSpeakingMask = 0
        )
        assertEquals(false, replayPlayingAction.audioFocusDucked)
        assertEquals(false, replayPlayingAction.setDucking)
        assertEquals(false, replayPlayingAction.setPaused)

        val netplayDeafenedAction = GameSessionPolicy.resolveAudioFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            isNetplay = true,
            showMenu = false,
            isReplayMode = false,
            replayManuallyPaused = false,
            voiceListenEnabled = false,
            remoteSpeakingMask = 0b11
        )
        assertEquals(false, netplayDeafenedAction.audioFocusDucked)
        assertEquals(false, netplayDeafenedAction.setDucking)
        assertEquals(false, netplayDeafenedAction.setPaused)

        val unknownAction = GameSessionPolicy.resolveAudioFocusChange(
            focusChange = AudioManager.AUDIOFOCUS_NONE,
            isNetplay = false,
            showMenu = false,
            isReplayMode = false,
            replayManuallyPaused = false,
            voiceListenEnabled = false,
            remoteSpeakingMask = 0
        )
        assertNull(unknownAction.audioFocusDucked)
        assertNull(unknownAction.setDucking)
        assertNull(unknownAction.setPaused)
    }

    @Test
    fun shouldResumeEngine_respectsMenuAndManualReplayPause() {
        assertFalse(GameSessionPolicy.shouldResumeEngine(showMenu = true, isReplayMode = false, replayManuallyPaused = false))
        assertFalse(GameSessionPolicy.shouldResumeEngine(showMenu = false, isReplayMode = true, replayManuallyPaused = true))
        assertTrue(GameSessionPolicy.shouldResumeEngine(showMenu = false, isReplayMode = true, replayManuallyPaused = false))
        assertTrue(GameSessionPolicy.shouldResumeEngine(showMenu = false, isReplayMode = false, replayManuallyPaused = true))
    }

    @Test
    fun playTimeTrackingAndFlushing_disabledInReplayMode() {
        assertFalse(GameSessionPolicy.shouldTrackPlayTime(isReplayMode = true, engineError = null, gameId = "sf2"))
        assertFalse(GameSessionPolicy.shouldFlushPlayTime(isReplayMode = true, gameId = "sf2"))
        assertFalse(GameSessionPolicy.shouldTrackPlayTime(isReplayMode = false, engineError = "err", gameId = "sf2"))
        assertFalse(GameSessionPolicy.shouldTrackPlayTime(isReplayMode = false, engineError = null, gameId = ""))
        assertFalse(GameSessionPolicy.shouldFlushPlayTime(isReplayMode = false, gameId = ""))
        assertTrue(GameSessionPolicy.shouldTrackPlayTime(isReplayMode = false, engineError = null, gameId = "sf2"))
        assertTrue(GameSessionPolicy.shouldFlushPlayTime(isReplayMode = false, gameId = "sf2"))
    }

    @Test
    fun scoreStatusLabel_hiddenInReplayAndNetplayAndFormattedInSolo() {
        assertNull(
            GameSessionPolicy.scoreStatusLabel(
                isNetplay = false,
                isReplayMode = true,
                scoreDef = """{"lboard":{}}""",
                rankingEligible = true,
                creditsUsed = 1,
                currentScore = 12300,
                bestSessionScore = 12300
            )
        )
        assertNull(
            GameSessionPolicy.scoreStatusLabel(
                isNetplay = true,
                isReplayMode = false,
                scoreDef = """{"lboard":{}}""",
                rankingEligible = true,
                creditsUsed = 1,
                currentScore = 12300,
                bestSessionScore = 12300
            )
        )
        assertEquals(
            "Ranking: 1 Crédito · Score 5000 (Máx 8000)",
            GameSessionPolicy.scoreStatusLabel(
                isNetplay = false,
                isReplayMode = false,
                scoreDef = """{"lboard":{}}""",
                rankingEligible = true,
                creditsUsed = 1,
                currentScore = 5000,
                bestSessionScore = 8000
            )
        )
        assertEquals(
            "Ranking: Desactivado (Save State)",
            GameSessionPolicy.scoreStatusLabel(
                isNetplay = false,
                isReplayMode = false,
                scoreDef = """{"lboard":{}}""",
                rankingEligible = false,
                creditsUsed = 1,
                currentScore = 5000,
                bestSessionScore = 8000
            )
        )
        assertEquals(
            "Ranking: Desactivado (2 créditos)",
            GameSessionPolicy.scoreStatusLabel(
                isNetplay = false,
                isReplayMode = false,
                scoreDef = """{"lboard":{}}""",
                rankingEligible = true,
                creditsUsed = 2,
                currentScore = 5000,
                bestSessionScore = 8000
            )
        )
        assertEquals(
            "Ranking: Sin configuración para este juego",
            GameSessionPolicy.scoreStatusLabel(
                isNetplay = false,
                isReplayMode = false,
                scoreDef = null,
                rankingEligible = true,
                creditsUsed = 1,
                currentScore = 0,
                bestSessionScore = 0
            )
        )
    }
}
