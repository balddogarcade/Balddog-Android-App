package dog.bald.arcade

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dog.bald.arcade.audio.VoiceAudioManager
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.PendingScoreQueue
import dog.bald.arcade.data.PendingScoreSubmission
import dog.bald.arcade.engine.NativeSurfaceBridge
import dog.bald.arcade.input.ControllerManager
import dog.bald.arcade.input.TouchLayoutConfig
import dog.bald.arcade.input.TouchLayoutPrefs
import dog.bald.arcade.ui.game.ArcadeTouchOverlay
import dog.bald.arcade.ui.game.InGameQuickMenu
import dog.bald.arcade.ui.game.MediaExportHelper
import dog.bald.arcade.ui.game.ReplayHudOverlay
import dog.bald.arcade.ui.game.ReplayVideoExporter
import dog.bald.arcade.ui.theme.BaldDogArcadeTheme
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.balddog_android.NetplayHudInfo
import uniffi.balddog_android.ReplayHudInfo
import uniffi.balddog_android.ScoreHudInfo
import uniffi.balddog_android.VoiceHudInfo
import uniffi.balddog_android.acknowledgeScoreSubmit
import uniffi.balddog_android.captureScreenshotPng
import uniffi.balddog_android.exportRecordedReplay
import uniffi.balddog_android.getCurrentFps
import uniffi.balddog_android.getNetplayHudStats
import uniffi.balddog_android.getReplayHudInfo
import uniffi.balddog_android.getScoreHudInfo
import uniffi.balddog_android.getVoiceHudState
import uniffi.balddog_android.loadGameState
import uniffi.balddog_android.logFromAndroid
import uniffi.balddog_android.pullVoicePlaybackPcm
import uniffi.balddog_android.pushVoiceCapturePcm
import uniffi.balddog_android.restartReplay
import uniffi.balddog_android.saveGameState
import uniffi.balddog_android.setAudioDucking
import uniffi.balddog_android.setGamePaused
import uniffi.balddog_android.setPlayerInput
import uniffi.balddog_android.setReplaySpeed
import uniffi.balddog_android.setVoiceControls
import uniffi.balddog_android.startGameWithScore
import uniffi.balddog_android.startNetplayGame
import uniffi.balddog_android.startReplayGame
import uniffi.balddog_android.startVoiceSession
import uniffi.balddog_android.stopGame
import uniffi.balddog_android.stopVoiceSession
import java.io.File

class GameActivity : ComponentActivity() {
    companion object {
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_SYSTEM_DIR = "system_dir"
        const val EXTRA_SAVE_DIR = "save_dir"
        const val EXTRA_GAME_TITLE = "game_title"
        const val EXTRA_ROM_SHA256 = "rom_sha256"
        const val EXTRA_SCORE_DEF = "score_def"
        const val EXTRA_NETPLAY_ENABLED = "netplay_enabled"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_LOCAL_HANDLE = "local_handle"
        const val EXTRA_NUM_PLAYERS = "num_players"
        const val EXTRA_PEER_HANDLES = "peer_handles"
        const val EXTRA_PEER_ADDRS = "peer_addrs"
        const val EXTRA_INPUT_DELAY = "input_delay"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_IS_HOST = "is_host"
        const val EXTRA_REPLAY_PATH = "replay_path"
        const val EXTRA_REPLAY_PLAYER_NAME = "replay_player_name"
        const val EXTRA_REPLAY_TARGET_SCORE = "replay_target_score"
        const val EXTRA_VOICE_LOCAL_PORT = "voice_local_port"
        const val EXTRA_VOICE_PEER_HANDLES = "voice_peer_handles"
        const val EXTRA_VOICE_PEER_ADDRS = "voice_peer_addrs"
    }

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var controllerManager: ControllerManager? = null
    private var voiceAudioManager: VoiceAudioManager? = null

    private var engineError by mutableStateOf<String?>(null)
    private var showMenu by mutableStateOf(false)
    private var editLayoutMode by mutableStateOf(false)
    private var hasPhysicalController by mutableStateOf(false)
    private var touchConfig by mutableStateOf(TouchLayoutConfig())
    private var menuStatusMsg by mutableStateOf<String?>(null)
    private var currentFps by mutableFloatStateOf(0f)
    private var isNetplay by mutableStateOf(false)
    private var netplayHud by mutableStateOf(NetplayHudInfo(0u, 0u, false, 0u, false))
    private var netplayEndReason by mutableStateOf<String?>(null)
    private var netplayRoomId: String = ""
    private var isNetplayHost: Boolean = false
    private var gameId: String = ""
    private var romSha256: String = ""
    private var scoreDef: String? = null
    private var scoreHud by mutableStateOf(ScoreHudInfo(0u, 0u, 0u, false, 1u))
    private var rankingNoticeShown: Boolean = false
    private var lastSubmittedSessionScore: Long = 0L
    private var isReplayMode by mutableStateOf(false)
    private var replayPath: String = ""
    private var replayPlayerName by mutableStateOf("")
    private var replayTargetScore by mutableStateOf(0L)
    private var replayHud by mutableStateOf(ReplayHudInfo(false, 0u, 0u, 1u, false, false))
    private var exportProgressPct by mutableStateOf<Int?>(null)
    private var voiceMicEnabled by mutableStateOf(false)
    private var voiceListenEnabled by mutableStateOf(false)
    private var voiceVolume by mutableFloatStateOf(1.0f)
    private var voiceHud by mutableStateOf(VoiceHudInfo(false, 0u, 0u))
    private var voiceNoticeMsg by mutableStateOf<String?>(null)

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                voiceMicEnabled = true
                syncVoiceControls()
            } else {
                voiceNoticeMsg = "Permiso de micrófono denegado"
            }
        }

    private val playTimerLock = Any()
    private var activePlayStartMs: Long? = null
    private var accumulatedActiveMs: Long = 0L
    private var reportedSeconds: Long = 0L
    private var sessionCounted: Boolean = false

    private var touchMaskP1: Int = 0
    private val physicalMasks = IntArray(4)

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> updateEnginePaused(true)
            AudioManager.AUDIOFOCUS_GAIN -> if (!showMenu) updateEnginePaused(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        touchConfig = TouchLayoutPrefs.load(this)
        voiceVolume = getSharedPreferences("balddog_voice_prefs", MODE_PRIVATE)
            .getFloat("voice_volume", 1.0f)
            .coerceIn(0f, 1.5f)

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH).orEmpty()
        val systemDir = intent.getStringExtra(EXTRA_SYSTEM_DIR).orEmpty()
        val saveDir = intent.getStringExtra(EXTRA_SAVE_DIR).orEmpty()
        val gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty()

        val stateDir = File(filesDir, "savestates").apply { mkdirs() }
        val romSlug = File(romPath).nameWithoutExtension.ifBlank { "default" }
        val stateFile = File(stateDir, "$romSlug.state")

        controllerManager = ControllerManager(
            context = this,
            onControllerPresenceChanged = { present -> hasPhysicalController = present },
            onPortMaskChanged = { port, mask ->
                physicalMasks[port] = mask
                pushInputToEngine(port)
            }
        ).also { it.start() }

        requestAudioFocus()

        isNetplay = intent.getBooleanExtra(EXTRA_NETPLAY_ENABLED, false)
        netplayRoomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        isNetplayHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        gameId = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        romSha256 = intent.getStringExtra(EXTRA_ROM_SHA256).orEmpty()
        scoreDef = intent.getStringExtra(EXTRA_SCORE_DEF)?.takeIf { it.isNotBlank() }
        replayPath = intent.getStringExtra(EXTRA_REPLAY_PATH).orEmpty()
        isReplayMode = replayPath.isNotBlank()
        replayPlayerName = intent.getStringExtra(EXTRA_REPLAY_PLAYER_NAME).orEmpty()
        replayTargetScore = intent.getLongExtra(EXTRA_REPLAY_TARGET_SCORE, 0L)

        try {
            if (romPath.isBlank()) {
                engineError = "No se especificó ruta de ROM"
            } else if (isReplayMode) {
                startReplayGame(
                    nativeLibDir = applicationInfo.nativeLibraryDir,
                    romPath = romPath,
                    systemDir = systemDir,
                    saveDir = saveDir,
                    replayPath = replayPath,
                    scoreDef = scoreDef
                )
            } else if (isNetplay) {
                val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 7000)
                val localHandle = intent.getIntExtra(EXTRA_LOCAL_HANDLE, 0)
                val numPlayers = intent.getIntExtra(EXTRA_NUM_PLAYERS, 2)
                val rawHandles = intent.getIntArrayExtra(EXTRA_PEER_HANDLES) ?: intArrayOf()
                val peerHandles = ByteArray(rawHandles.size) { rawHandles[it].toByte() }
                val peerAddrs = intent.getStringArrayListExtra(EXTRA_PEER_ADDRS) ?: arrayListOf()
                val inputDelay = intent.getIntExtra(EXTRA_INPUT_DELAY, 2)
                startNetplayGame(
                    nativeLibDir = applicationInfo.nativeLibraryDir,
                    romPath = romPath,
                    systemDir = systemDir,
                    saveDir = saveDir,
                    localPort = localPort.toUShort(),
                    localPlayerHandle = localHandle.toUByte(),
                    numPlayers = numPlayers.toUByte(),
                    peerHandles = peerHandles,
                    peerAddrs = peerAddrs,
                    inputDelay = inputDelay.toUByte()
                )
                val voiceLocalPort = intent.getIntExtra(EXTRA_VOICE_LOCAL_PORT, localPort + 1000)
                val rawVoiceHandles = intent.getIntArrayExtra(EXTRA_VOICE_PEER_HANDLES) ?: intArrayOf()
                val voicePeerHandles = ByteArray(rawVoiceHandles.size) { rawVoiceHandles[it].toByte() }
                val voicePeerAddrs = intent.getStringArrayListExtra(EXTRA_VOICE_PEER_ADDRS) ?: arrayListOf()
                runCatching {
                    startVoiceSession(
                        localPort = voiceLocalPort.toUShort(),
                        roomId = netplayRoomId.ifBlank { "default-room" },
                        localPlayerHandle = localHandle.toUByte(),
                        peerHandles = voicePeerHandles,
                        peerAddrs = voicePeerAddrs
                    )
                    setVoiceControls(
                        micEnabled = false,
                        listenEnabled = false,
                        volume = voiceVolume,
                        paused = false
                    )
                    voiceAudioManager = VoiceAudioManager(
                        context = this,
                        onCapturePcm = { frame -> pushVoiceCapturePcm(frame.toList()) },
                        onPullPlaybackPcm = { maxSamples -> pullVoicePlaybackPcm(maxSamples.toUInt()).toShortArray() }
                    )
                }
            } else {
                logFromAndroid(
                    "INFO",
                    "Iniciando juego solo gameId=$gameId hasScoreDef=${scoreDef != null}"
                )
                startGameWithScore(
                    nativeLibDir = applicationInfo.nativeLibraryDir,
                    romPath = romPath,
                    systemDir = systemDir,
                    saveDir = saveDir,
                    scoreDef = scoreDef
                )
            }
        } catch (t: Throwable) {
            engineError = t.message ?: "Error iniciando emulador"
        }

        setContent {
            BaldDogArcadeTheme {
                LaunchedEffect(showMenu) {
                    while (true) {
                        currentFps = getCurrentFps()
                        if (isNetplay) {
                            netplayHud = getNetplayHudStats()
                            if (netplayHud.sessionEnded && netplayEndReason == null) {
                                netplayEndReason = "El otro jugador se desconectó de la partida."
                            }
                        }
                        if (!showMenu) {
                            flushPlayTime(force = false)
                        }
                        if (!isNetplay && !isReplayMode && scoreDef != null) {
                            val hud = getScoreHudInfo()
                            scoreHud = hud
                            pollAndSubmitScore(hud)
                        }
                        if (isReplayMode) {
                            replayHud = getReplayHudInfo()
                            if (scoreDef != null) {
                                scoreHud = getScoreHudInfo()
                            }
                        }
                        delay(if (isReplayMode) 200 else 500)
                    }
                }

                // Polling rápido de voz para pulso de micrófono, badge de hablante y ducking
                LaunchedEffect(isNetplay) {
                    if (isNetplay) {
                        while (true) {
                            voiceHud = getVoiceHudState()
                            val someoneSpeaking = voiceListenEnabled && voiceHud.remoteSpeakingMask.toInt() != 0
                            setAudioDucking(someoneSpeaking)
                            delay(150)
                        }
                    }
                }

                LaunchedEffect(voiceNoticeMsg) {
                    if (voiceNoticeMsg != null) {
                        delay(3000)
                        voiceNoticeMsg = null
                    }
                }

                // Vigilar estado de la sala en Supabase: si el Host cierra la sala, avisar y desbloquear
                LaunchedEffect(isNetplay, netplayRoomId) {
                    if (isNetplay && netplayRoomId.isNotBlank()) {
                        val repo = ArcadeRepository(applicationContext)
                        while (netplayEndReason == null) {
                            delay(2000)
                            val r = runCatching { repo.fetchRoomById(netplayRoomId) }.getOrNull()
                            if (r == null || r.status == "closed") {
                                netplayEndReason = "El anfitrión se fue de la sala."
                                stopGame()
                                break
                            }
                        }
                    }
                }

                BackHandler {
                    if (editLayoutMode) {
                        editLayoutMode = false
                        TouchLayoutPrefs.save(this@GameActivity, touchConfig)
                    } else {
                        showMenu = !showMenu
                        updateEnginePaused(showMenu)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianBlack)
                ) {
                    if (engineError == null) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    var attached = false
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            val rect = holder.surfaceFrame
                                            if (rect.width() > 1 && rect.height() > 1) {
                                                NativeSurfaceBridge.attachSurface(
                                                    holder.surface,
                                                    rect.width(),
                                                    rect.height()
                                                )
                                                attached = true
                                            }
                                        }

                                        override fun surfaceChanged(
                                            holder: SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int
                                        ) {
                                            if (!attached && width > 0 && height > 0) {
                                                NativeSurfaceBridge.attachSurface(holder.surface, width, height)
                                                attached = true
                                            } else {
                                                NativeSurfaceBridge.resizeSurface(width, height)
                                            }
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            attached = false
                                            NativeSurfaceBridge.detachSurface()
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        val shouldShowTouch = !isReplayMode && (!hasPhysicalController || touchConfig.forceShowTouch || editLayoutMode)
                        if (shouldShowTouch && !showMenu) {
                            ArcadeTouchOverlay(
                                config = touchConfig,
                                editMode = editLayoutMode,
                                onConfigChange = { updated -> touchConfig = updated },
                                onMaskChanged = { mask ->
                                    touchMaskP1 = mask
                                    pushInputToEngine(0)
                                }
                            )
                        }
                        if (isReplayMode && !showMenu) {
                            ReplayHudOverlay(
                                playerName = replayPlayerName,
                                targetScore = replayTargetScore,
                                currentScore = scoreHud.currentScore.toLong(),
                                replayHud = replayHud,
                                exportProgressPct = exportProgressPct,
                                onTogglePause = {
                                    val nextPaused = !replayHud.paused
                                    updateEnginePaused(nextPaused)
                                    replayHud = getReplayHudInfo()
                                },
                                onRestart = {
                                    restartReplay()
                                    updateEnginePaused(false)
                                },
                                onSelectSpeed = { spd ->
                                    setReplaySpeed(spd.toUByte())
                                    replayHud = getReplayHudInfo()
                                },
                                onCaptureScreenshot = {
                                    captureAndSaveScreenshot(gameTitle.ifBlank { "arcade" })
                                },
                                onExportVideoClip = {
                                    exportReplayVideoClip(
                                        romPath = romPath,
                                        systemDir = systemDir,
                                        saveDir = saveDir,
                                        title = gameTitle.ifBlank { "arcade" }
                                    )
                                },
                                onExit = {
                                    stopGame()
                                    finish()
                                }
                            )
                        }

                        if (!showMenu) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 16.dp, end = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isNetplay) {
                                    val speakerMask = voiceHud.remoteSpeakingMask.toInt()
                                    if (voiceListenEnabled && speakerMask != 0) {
                                        val speakingPlayers = (0 until 4)
                                            .filter { (speakerMask and (1 shl it)) != 0 }
                                            .joinToString("·") { "P${it + 1}" }
                                        if (speakingPlayers.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .background(PureWhite.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = speakingPlayers,
                                                    color = PureBlack,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }

                                    // Toggle Micrófono (Mute / Unmute)
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (voiceMicEnabled) PureWhite.copy(alpha = 0.18f) else CarbonSurface.copy(alpha = 0.8f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .border(
                                                width = if (voiceHud.localSpeaking) 2.dp else 1.dp,
                                                color = when {
                                                    voiceHud.localSpeaking -> PureWhite
                                                    voiceMicEnabled -> SilverGray
                                                    else -> SteelBorder
                                                },
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable { toggleVoiceMic() }
                                            .padding(horizontal = 9.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (voiceMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                            contentDescription = if (voiceMicEnabled) "Silenciar micrófono" else "Activar micrófono",
                                            tint = if (voiceMicEnabled) PureWhite else SilverGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Toggle Audio de otros jugadores (Deafen / Undeafen)
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (voiceListenEnabled) PureWhite.copy(alpha = 0.18f) else CarbonSurface.copy(alpha = 0.8f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (voiceListenEnabled) SilverGray else SteelBorder,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable { toggleVoiceListen() }
                                            .padding(horizontal = 9.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (voiceListenEnabled) Icons.Default.Headset else Icons.Default.HeadsetOff,
                                            contentDescription = if (voiceListenEnabled) "Silenciar a los demás" else "Escuchar a los demás",
                                            tint = if (voiceListenEnabled) PureWhite else SilverGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .background(CarbonSurface.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                                        .border(1.dp, if (editLayoutMode) PureWhite else SteelBorder, RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (editLayoutMode) {
                                                editLayoutMode = false
                                                TouchLayoutPrefs.save(this@GameActivity, touchConfig)
                                            } else {
                                                menuStatusMsg = null
                                                showMenu = true
                                                updateEnginePaused(true)
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = when {
                                            editLayoutMode -> "GUARDAR LAYOUT"
                                            isNetplay -> {
                                                val pingLabel = if (netplayHud.pingMs > 0u) "${netplayHud.pingMs} ms" else "-- ms"
                                                "MENU · ${"%.0f".format(currentFps)} FPS · $pingLabel"
                                            }
                                            else -> "MENU · ${"%.0f".format(currentFps)} FPS"
                                        },
                                        color = if (editLayoutMode) PureWhite else SilverGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            val notice = voiceNoticeMsg
                            if (notice != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 52.dp, end = 20.dp)
                                        .background(CarbonSurface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                                        .border(1.dp, SteelBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(text = notice, color = PureWhite, fontSize = 11.sp)
                                }
                            }
                            if (isNetplay && netplayHud.reconnecting) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 16.dp)
                                        .background(PureWhite.copy(alpha = 0.94f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "RECONECTANDO PAR... ${netplayHud.reconnectSecsLeft}s",
                                        color = PureBlack,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        } else {
                            val scoreLabel = when {
                                isNetplay -> null
                                scoreDef == null -> "Ranking: Sin configuración para este juego"
                                !scoreHud.rankingEligible -> "Ranking: Desactivado (Save State)"
                                scoreHud.creditsUsed.toInt() > 1 -> "Ranking: Desactivado (${scoreHud.creditsUsed} créditos)"
                                else -> "Ranking: 1 Crédito · Score ${scoreHud.currentScore} (Máx ${scoreHud.bestSessionScore})"
                            }
                            InGameQuickMenu(
                                gameTitle = gameTitle,
                                fps = currentFps,
                                statusMessage = menuStatusMsg,
                                hasPhysicalController = hasPhysicalController,
                                touchConfig = touchConfig,
                                editLayoutMode = editLayoutMode,
                                isNetplay = isNetplay,
                                scoreStatusLabel = scoreLabel,
                                voiceVolume = voiceVolume,
                                onVoiceVolumeChange = { updated ->
                                    voiceVolume = updated
                                    getSharedPreferences("balddog_voice_prefs", MODE_PRIVATE)
                                        .edit()
                                        .putFloat("voice_volume", updated)
                                        .apply()
                                    syncVoiceControls()
                                },
                                onTouchConfigChange = { updated ->
                                    touchConfig = updated
                                    TouchLayoutPrefs.save(this@GameActivity, updated)
                                },
                                onToggleEditLayout = {
                                    editLayoutMode = true
                                    showMenu = false
                                    updateEnginePaused(false)
                                },
                                onResetLayout = {
                                    touchConfig = TouchLayoutConfig(forceShowTouch = touchConfig.forceShowTouch)
                                    TouchLayoutPrefs.save(this@GameActivity, touchConfig)
                                    menuStatusMsg = "Layout restaurado"
                                },
                                onSaveState = {
                                    try {
                                        saveGameState(stateFile.absolutePath)
                                        menuStatusMsg = "Partida guardada"
                                    } catch (t: Throwable) {
                                        menuStatusMsg = t.message ?: "Error al guardar"
                                    }
                                },
                                onLoadState = {
                                    try {
                                        if (stateFile.exists()) {
                                            loadGameState(stateFile.absolutePath)
                                            menuStatusMsg = "Partida cargada"
                                        } else {
                                            menuStatusMsg = "No hay estado guardado aún"
                                        }
                                    } catch (t: Throwable) {
                                        menuStatusMsg = t.message ?: "Error al cargar"
                                    }
                                },
                                onResume = {
                                    showMenu = false
                                    updateEnginePaused(false)
                                },
                                onExit = { finish() }
                            )
                        }
                    } else {
                        Text(
                            text = engineError ?: "",
                            color = SilverGray,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
                    }

                    val endMsg = netplayEndReason
                    if (endMsg != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ObsidianBlack.copy(alpha = 0.84f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("PARTIDA FINALIZADA", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(endMsg, color = MutedGray, fontSize = 13.sp)
                                    Button(
                                        onClick = { finish() },
                                        colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
                                    ) {
                                        Text("VOLVER A SALAS", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pushInputToEngine(port: Int) {
        val mask = if (port == 0) {
            touchMaskP1 or physicalMasks[0]
        } else {
            physicalMasks[port]
        }
        setPlayerInput(port.toUByte(), mask.toUShort())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controllerManager?.handleKeyEvent(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (controllerManager?.handleMotionEvent(ev) == true) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun requestAudioFocus() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        focusRequest = req
        manager.requestAudioFocus(req)
    }

    override fun onPause() {
        super.onPause()
        updateEnginePaused(true)
        if (isNetplay) {
            voiceAudioManager?.setPaused(true)
            setVoiceControls(voiceMicEnabled, voiceListenEnabled, voiceVolume, paused = true)
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        if (!showMenu) {
            updateEnginePaused(false)
        }
        if (isNetplay) {
            voiceAudioManager?.setPaused(false)
            setVoiceControls(voiceMicEnabled, voiceListenEnabled, voiceVolume, paused = false)
        }
    }

    override fun onStop() {
        super.onStop()
        pausePlayTimer()
        flushPlayTime(force = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        pausePlayTimer()
        flushPlayTime(force = true)
        if (isNetplay && isNetplayHost && netplayRoomId.isNotBlank()) {
            val roomIdToClose = netplayRoomId
            val appCtx = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { ArcadeRepository(appCtx).deleteRoom(roomIdToClose) }
            }
        }
        controllerManager?.stop()
        flushBestScoreOnExit()
        voiceAudioManager?.release()
        voiceAudioManager = null
        stopVoiceSession()
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        NativeSurfaceBridge.detachSurface()
        stopGame()
    }

    private fun syncVoiceControls() {
        setVoiceControls(
            micEnabled = voiceMicEnabled,
            listenEnabled = voiceListenEnabled,
            volume = voiceVolume,
            paused = false
        )
        voiceAudioManager?.setCaptureEnabled(voiceMicEnabled)
        voiceAudioManager?.setPlaybackEnabled(voiceListenEnabled)
        if (!voiceListenEnabled) {
            setAudioDucking(false)
        }
    }

    private fun toggleVoiceMic() {
        if (!voiceMicEnabled) {
            val hasPerm = voiceAudioManager?.hasRecordPermission() == true
            if (!hasPerm) {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            voiceMicEnabled = true
        } else {
            voiceMicEnabled = false
        }
        syncVoiceControls()
    }

    private fun toggleVoiceListen() {
        voiceListenEnabled = !voiceListenEnabled
        syncVoiceControls()
    }

    private fun updateEnginePaused(paused: Boolean) {
        setGamePaused(paused)
        if (paused) pausePlayTimer() else resumePlayTimer()
    }

    private fun resumePlayTimer() {
        if (engineError != null || gameId.isBlank()) return
        synchronized(playTimerLock) {
            if (activePlayStartMs == null) {
                activePlayStartMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun pausePlayTimer() {
        synchronized(playTimerLock) {
            val start = activePlayStartMs ?: return
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
            accumulatedActiveMs += elapsed
            activePlayStartMs = null
        }
    }

    private fun flushPlayTime(force: Boolean) {
        val gid = gameId
        if (gid.isBlank()) return

        var deltaToSend = 0L
        var isNewSession = false
        synchronized(playTimerLock) {
            val now = SystemClock.elapsedRealtime()
            val start = activePlayStartMs
            if (start != null) {
                accumulatedActiveMs += (now - start).coerceAtLeast(0L)
                activePlayStartMs = now
            }
            val totalSec = accumulatedActiveMs / 1000L
            val delta = totalSec - reportedSeconds
            if (delta >= 60L || (force && delta > 0L)) {
                deltaToSend = delta
                reportedSeconds = totalSec
                isNewSession = !sessionCounted
                sessionCounted = true
            }
        }

        if (deltaToSend > 0L) {
            val appCtx = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    ArcadeRepository(appCtx).recordPlaySession(gid, deltaToSend, isNewSession)
                }
            }
        }
    }

    private fun pollAndSubmitScore(hud: ScoreHudInfo) {
        val credits = hud.creditsUsed.toInt()
        if ((!hud.rankingEligible || credits > 1) && !rankingNoticeShown) {
            rankingNoticeShown = true
            voiceNoticeMsg = if (credits > 1) {
                "Ranking 1-Crédito desactivado (usaste Continue)"
            } else {
                "Ranking desactivado en esta sesión (Save State)"
            }
        }

        val pending = hud.pendingSubmitScore.toLong()
        if (pending <= 0L) return
        acknowledgeScoreSubmit()
        logFromAndroid(
            "INFO",
            "Score trigger recibido: score=$pending eligible=${hud.rankingEligible} credits=$credits"
        )

        if (!hud.rankingEligible || credits > 1) return
        submitOrQueueScore(score = pending, credits = credits, notifyUi = true)
    }

    private fun flushBestScoreOnExit() {
        if (isNetplay || scoreDef == null) return
        val hud = runCatching { getScoreHudInfo() }.getOrNull() ?: return
        val credits = hud.creditsUsed.toInt()
        if (!hud.rankingEligible || credits > 1) return

        val candidate = maxOf(hud.pendingSubmitScore.toLong(), hud.bestSessionScore.toLong())
        if (candidate <= lastSubmittedSessionScore) return
        acknowledgeScoreSubmit()
        logFromAndroid(
            "INFO",
            "Envío de score al salir: score=$candidate credits=$credits"
        )
        submitOrQueueScore(score = candidate, credits = credits, notifyUi = false)
    }

    private fun submitOrQueueScore(score: Long, credits: Int, notifyUi: Boolean) {
        if (score <= 0L || gameId.isBlank() || romSha256.isBlank()) return
        if (score <= lastSubmittedSessionScore) return
        lastSubmittedSessionScore = score

        val appCtx = applicationContext
        val gid = gameId
        val sha = romSha256
        val replayFile = File(filesDir, "pending_replays/${gid}.bsv.gz")
        val exportedReplay = runCatching {
            exportRecordedReplay(replayFile.absolutePath)
            replayFile.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()

        CoroutineScope(Dispatchers.IO).launch {
            val repo = ArcadeRepository(appCtx)
            val uploadedReplay = exportedReplay?.let { file ->
                runCatching { repo.uploadReplay(gid, file) }.getOrNull()
            }
            val result = runCatching {
                repo.submitHighScore(
                    gameId = gid,
                    score = score,
                    romSha256 = sha,
                    creditsUsed = credits,
                    isHardcore = true,
                    replayPath = uploadedReplay?.first,
                    replaySizeBytes = uploadedReplay?.second,
                    replaySha256 = uploadedReplay?.third
                )
            }
            val dto = result.getOrNull()
            if (result.isSuccess && dto != null) {
                exportedReplay?.delete()
                logFromAndroid(
                    "INFO",
                    "submit_high_score resp: accepted=${dto.accepted} new=${dto.isNewRecord} rank=${dto.rank} reason=${dto.reason}"
                )
                if (notifyUi && dto.accepted && dto.isNewRecord) {
                    val rankText = dto.rank?.let { " · Puesto #$it" }.orEmpty()
                    voiceNoticeMsg = "¡Nuevo récord personal: $score$rankText!"
                } else if (!dto.accepted) {
                    Log.w("BaldDogScore", "Score rechazado por servidor: ${dto.reason}")
                }
            } else {
                val errMsg = result.exceptionOrNull()?.message ?: "unknown"
                logFromAndroid("WARN", "submit_high_score falló ($errMsg), encolando offline")
                PendingScoreQueue.enqueue(
                    appCtx,
                    PendingScoreSubmission(
                        gameId = gid,
                        score = score,
                        romSha256 = sha,
                        creditsUsed = credits,
                        isHardcore = true,
                        replayLocalPath = exportedReplay?.absolutePath
                    )
                )
                if (notifyUi) {
                    voiceNoticeMsg = "Récord guardado offline ($score)"
                }
            }
        }
    }

    private fun captureAndSaveScreenshot(title: String) {
        val appCtx = applicationContext
        val tmpPng = File(cacheDir, "screenshots/shot_${System.currentTimeMillis()}.png")
        CoroutineScope(Dispatchers.IO).launch {
            val captured = runCatching {
                captureScreenshotPng(tmpPng.absolutePath)
                MediaExportHelper.savePngToGallery(appCtx, tmpPng, title).getOrThrow()
            }
            tmpPng.delete()
            voiceNoticeMsg = if (captured.isSuccess) {
                "Captura guardada: ${captured.getOrNull()}"
            } else {
                "Error al capturar pantalla"
            }
        }
    }

    private fun exportReplayVideoClip(
        romPath: String,
        systemDir: String,
        saveDir: String,
        title: String,
    ) {
        if (exportProgressPct != null || replayPath.isBlank()) return
        exportProgressPct = 0
        val appCtx = applicationContext
        val nativeDir = applicationInfo.nativeLibraryDir
        val rPath = replayPath
        val sDef = scoreDef
        CoroutineScope(Dispatchers.IO).launch {
            stopGame()
            val res = ReplayVideoExporter.exportToGallery(
                context = appCtx,
                nativeLibDir = nativeDir,
                romPath = romPath,
                systemDir = systemDir,
                saveDir = saveDir,
                replayPath = rPath,
                gameTitle = title,
                onProgress = { pct -> exportProgressPct = pct }
            )
            exportProgressPct = null
            runCatching {
                startReplayGame(
                    nativeLibDir = nativeDir,
                    romPath = romPath,
                    systemDir = systemDir,
                    saveDir = saveDir,
                    replayPath = rPath,
                    scoreDef = sDef
                )
            }
            voiceNoticeMsg = if (res.isSuccess) {
                "Clip MP4 guardado en Movies/balddog ARCADE"
            } else {
                "Error exportando MP4: ${res.exceptionOrNull()?.message ?: "desconocido"}"
            }
        }
    }
}
