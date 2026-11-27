package dog.bald.arcade

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dog.bald.arcade.audio.VoiceAudioManager
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.ExitSyncCoordinator
import dog.bald.arcade.data.MatchHistoryPlayerDto
import dog.bald.arcade.data.PendingMatchHistoryEntry
import dog.bald.arcade.data.PendingMatchHistoryQueue
import dog.bald.arcade.data.PendingScoreQueue
import dog.bald.arcade.data.PendingScoreSubmission
import dog.bald.arcade.diagnostics.AnalyticsHelper
import dog.bald.arcade.diagnostics.CrashlyticsHelper
import dog.bald.arcade.diagnostics.PerformanceHelper
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
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
import uniffi.balddog_android.seekReplay
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
        const val EXTRA_NETPLAY_MODE = "netplay_mode"
        const val EXTRA_ROOM_IS_PRIVATE = "room_is_private"
        const val EXTRA_ROOM_PLAYERS_JSON = "room_players_json"
        const val EXTRA_IS_HOST = "is_host"
        const val EXTRA_REPLAY_PATH = "replay_path"
        const val EXTRA_REPLAY_PLAYER_NAME = "replay_player_name"
        const val EXTRA_REPLAY_TARGET_SCORE = "replay_target_score"
        const val EXTRA_VOICE_LOCAL_PORT = "voice_local_port"
        const val EXTRA_VOICE_PEER_HANDLES = "voice_peer_handles"
        const val EXTRA_VOICE_PEER_ADDRS = "voice_peer_addrs"

        private val jsonFormat = Json { ignoreUnknownKeys = true }
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
    private var netplayPeerAddrs by mutableStateOf<List<String>>(emptyList())
    private var netplayHud by mutableStateOf(NetplayHudInfo(0u, 0u, false, 0u, false, false))
    private var startupTrace: com.google.firebase.perf.metrics.Trace? = null
    private var netplayEndReason by mutableStateOf<String?>(null)
    private var netplayRoomId: String = ""
    private var netplayMode: String = "online"
    private var roomIsPrivate: Boolean = false
    private var roomPlayersJson: String = "[]"
    private var isNetplayHost: Boolean = false
    private var matchSessionId: String = ""
    private var matchStartedAtIso: String = ""
    private var matchHistoryRecorded: Boolean = false
    private var gameId: String = ""
    private var romSha256: String = ""
    private var scoreDef: String? = null
    private var scoreHud by mutableStateOf(ScoreHudInfo(0u, 0u, 0u, false, 1u))
    private var rankingNoticeShown: Boolean = false
    private var lastSubmittedSessionScore: Long = 0L
    private var isReplayMode by mutableStateOf(false)
    private var replayManuallyPaused by mutableStateOf(false)

    @Volatile
    private var audioFocusDucked: Boolean = false
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

    private var pendingStorageExportAction: (() -> Unit)? = null

    private val writeStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingStorageExportAction
            pendingStorageExportAction = null
            if (granted) {
                pending?.invoke()
            } else {
                voiceNoticeMsg = "Permiso de almacenamiento denegado"
            }
        }

    private val playTimerLock = Any()
    private var activePlayStartMs: Long? = null
    private var accumulatedActiveMs: Long = 0L
    private var reportedSeconds: Long = 0L
    private var sessionCounted: Boolean = false

    private val pendingExportLock = Any()
    private val pendingReplayExports = mutableListOf<CompletableDeferred<Unit>>()

    private var touchMaskP1: Int = 0
    private val physicalMasks = IntArray(4)

    private val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            val action =
                GameSessionPolicy.resolveAudioFocusChange(
                    focusChange = change,
                    isNetplay = isNetplay,
                    showMenu = showMenu,
                    isReplayMode = isReplayMode,
                    replayManuallyPaused = replayManuallyPaused,
                    voiceListenEnabled = voiceListenEnabled,
                    remoteSpeakingMask = voiceHud.remoteSpeakingMask.toInt(),
                )
            action.audioFocusDucked?.let { ducked ->
                audioFocusDucked = ducked
            }
            action.setDucking?.let { ducking ->
                setAudioDucking(ducking)
            }
            action.setPaused?.let { paused ->
                updateEnginePaused(paused)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        touchConfig = TouchLayoutPrefs.load(this)
        voiceVolume =
            getSharedPreferences("balddog_voice_prefs", MODE_PRIVATE)
                .getFloat("voice_volume", 1.0f)
                .coerceIn(0f, 1.5f)

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH).orEmpty()
        val systemDir = intent.getStringExtra(EXTRA_SYSTEM_DIR).orEmpty()
        val saveDir = intent.getStringExtra(EXTRA_SAVE_DIR).orEmpty()
        val gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty()

        val stateDir = File(filesDir, "savestates").apply { mkdirs() }
        val romSlug = File(romPath).nameWithoutExtension.ifBlank { "default" }
        val stateFile = File(stateDir, "$romSlug.state")

        controllerManager =
            ControllerManager(
                context = this,
                onControllerPresenceChanged = { present -> hasPhysicalController = present },
                onPortMaskChanged = { port, mask ->
                    physicalMasks[port] = mask
                    pushInputToEngine(port)
                },
            ).also { it.start() }

        requestAudioFocus()

        isNetplay = intent.getBooleanExtra(EXTRA_NETPLAY_ENABLED, false)
        netplayRoomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        netplayMode = intent.getStringExtra(EXTRA_NETPLAY_MODE).orEmpty().ifBlank { "online" }
        roomIsPrivate = intent.getBooleanExtra(EXTRA_ROOM_IS_PRIVATE, false)
        roomPlayersJson = intent.getStringExtra(EXTRA_ROOM_PLAYERS_JSON).orEmpty().ifBlank { "[]" }
        isNetplayHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        matchSessionId =
            if (isNetplay && netplayRoomId.isNotBlank()) {
                netplayRoomId
            } else {
                java.util.UUID
                    .randomUUID()
                    .toString()
            }
        matchStartedAtIso =
            java.time.Instant
                .now()
                .toString()
        gameId = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        romSha256 = intent.getStringExtra(EXTRA_ROM_SHA256).orEmpty()
        scoreDef = intent.getStringExtra(EXTRA_SCORE_DEF)?.takeIf { it.isNotBlank() }
        replayPath = intent.getStringExtra(EXTRA_REPLAY_PATH).orEmpty()
        isReplayMode = replayPath.isNotBlank()
        replayPlayerName = intent.getStringExtra(EXTRA_REPLAY_PLAYER_NAME).orEmpty()
        replayTargetScore = intent.getLongExtra(EXTRA_REPLAY_TARGET_SCORE, 0L)

        val crashNetplayMode =
            when {
                isReplayMode -> "replay"
                isNetplay && isNetplayHost -> "host"
                isNetplay -> "client"
                else -> "offline"
            }
        CrashlyticsHelper.setGameContext(
            gameId = gameId,
            gameTitle = gameTitle,
            romSlug = romSlug,
            romSha256 = romSha256,
            netplayMode = crashNetplayMode,
        )
        CrashlyticsHelper.setNetplayState(
            state = if (isNetplay) "synchronizing" else "running",
            roomId = netplayRoomId,
        )
        CrashlyticsHelper.log("GameActivity start: mode=$crashNetplayMode gameId=$gameId rom=$romSlug")
        AnalyticsHelper.logGameLaunch(gameId = gameId, romSlug = romSlug, mode = crashNetplayMode)
        startupTrace = PerformanceHelper.startTrace("rom_startup_trace")
        var netplaySyncTrace = if (isNetplay) PerformanceHelper.startTrace("netplay_sync_trace") else null

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
                    scoreDef = scoreDef,
                )
            } else if (isNetplay) {
                val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 7000)
                val localHandle = intent.getIntExtra(EXTRA_LOCAL_HANDLE, 0)
                val numPlayers = intent.getIntExtra(EXTRA_NUM_PLAYERS, 2)
                val rawHandles = intent.getIntArrayExtra(EXTRA_PEER_HANDLES) ?: intArrayOf()
                val peerHandles = ByteArray(rawHandles.size) { rawHandles[it].toByte() }
                val peerAddrs = intent.getStringArrayListExtra(EXTRA_PEER_ADDRS) ?: arrayListOf()
                netplayPeerAddrs = peerAddrs
                val inputDelay = intent.getIntExtra(EXTRA_INPUT_DELAY, 2)
                logFromAndroid(
                    "INFO",
                    "startNetplayGame: localPort=$localPort, localHandle=$localHandle, numPlayers=$numPlayers, peerHandles=${peerHandles.toList()}, peerAddrs=$peerAddrs",
                )
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
                    inputDelay = inputDelay.toUByte(),
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
                        peerAddrs = voicePeerAddrs,
                    )
                    setVoiceControls(
                        micEnabled = false,
                        listenEnabled = false,
                        volume = voiceVolume,
                        paused = false,
                    )
                    voiceAudioManager =
                        VoiceAudioManager(
                            context = this,
                            onCapturePcm = { frame -> pushVoiceCapturePcm(frame.toList()) },
                            onPullPlaybackPcm = { maxSamples -> pullVoicePlaybackPcm(maxSamples.toUInt()).toShortArray() },
                        )
                }
            } else {
                logFromAndroid(
                    "INFO",
                    "Iniciando juego solo gameId=$gameId hasScoreDef=${scoreDef != null}",
                )
                startGameWithScore(
                    nativeLibDir = applicationInfo.nativeLibraryDir,
                    romPath = romPath,
                    systemDir = systemDir,
                    saveDir = saveDir,
                    scoreDef = scoreDef,
                )
            }
        } catch (t: Throwable) {
            CrashlyticsHelper.recordNonFatal(t)
            engineError = t.message ?: "Error iniciando emulador"
        }

        setContent {
            BaldDogArcadeTheme {
                var netplaySynchronized by remember { mutableStateOf(false) }
                var syncSecondsElapsed by remember { mutableIntStateOf(0) }
                var syncTimedOut by remember { mutableStateOf(false) }

                LaunchedEffect(isNetplay, netplaySynchronized) {
                    if (isNetplay && !netplaySynchronized) {
                        while (!netplaySynchronized && !syncTimedOut) {
                            delay(1000)
                            syncSecondsElapsed++
                            if (syncSecondsElapsed >= 25) {
                                syncTimedOut = true
                                logFromAndroid("WARN", "Netplay timeout de 25s alcanzado sin sincronización P2P")
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        currentFps = getCurrentFps()
                        if (isNetplay) {
                            netplayHud = getNetplayHudStats()
                            if (netplayHud.synchronized || netplayHud.pingMs > 0u) {
                                if (!netplaySynchronized) {
                                    logFromAndroid("INFO", "Netplay sincronizado exitosamente! Ping=${netplayHud.pingMs}ms")
                                    CrashlyticsHelper.setNetplayState("running", netplayRoomId, netplayHud.pingMs)
                                    CrashlyticsHelper.log("GGRS synchronized ping=${netplayHud.pingMs}ms")
                                    PerformanceHelper.stopTrace(
                                        netplaySyncTrace,
                                        mapOf("rom_slug" to gameId, "mode" to crashNetplayMode),
                                    )
                                    netplaySyncTrace = null
                                }
                                netplaySynchronized = true
                            }
                            if (netplayHud.sessionEnded && netplayEndReason == null) {
                                CrashlyticsHelper.setNetplayState("ended", netplayRoomId, netplayHud.pingMs)
                                netplayEndReason = "El otro jugador se desconectó de la partida."
                            }
                        }
                        if (!showMenu && !isReplayMode) {
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
                            setAudioDucking(audioFocusDucked || someoneSpeaking)
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
                        var consecutiveErrors = 0
                        while (netplayEndReason == null) {
                            delay(2000)
                            val result = runCatching { repo.fetchRoomById(netplayRoomId) }
                            if (result.isFailure) {
                                consecutiveErrors++
                                if (consecutiveErrors >= 5) {
                                    netplayEndReason = "Se perdió la conexión con el servidor de salas."
                                    stopGame()
                                    break
                                }
                                continue
                            }
                            consecutiveErrors = 0
                            val r = result.getOrNull()
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
                        updateEnginePaused(showMenu || (isReplayMode && replayManuallyPaused))
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(ObsidianBlack),
                ) {
                    if (engineError == null) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    var attached = false
                                    holder.addCallback(
                                        object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                val rect = holder.surfaceFrame
                                                if (rect.width() > 1 && rect.height() > 1) {
                                                    NativeSurfaceBridge.attachSurface(
                                                        holder.surface,
                                                        rect.width(),
                                                        rect.height(),
                                                    )
                                                    attached = true
                                                }
                                            }

                                            override fun surfaceChanged(
                                                holder: SurfaceHolder,
                                                format: Int,
                                                width: Int,
                                                height: Int,
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
                                                startupTrace?.stop()
                                                startupTrace = null
                                                NativeSurfaceBridge.detachSurface()
                                            }
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
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
                                },
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
                                    replayManuallyPaused = nextPaused
                                    updateEnginePaused(nextPaused)
                                    replayHud = getReplayHudInfo()
                                },
                                onRestart = {
                                    replayManuallyPaused = false
                                    restartReplay()
                                    updateEnginePaused(false)
                                    replayHud = getReplayHudInfo()
                                },
                                onSeekToFrame = { targetFrame ->
                                    seekReplay(targetFrame.toULong())
                                    replayHud = getReplayHudInfo()
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
                                        title = gameTitle.ifBlank { "arcade" },
                                    )
                                },
                                onExit = {
                                    stopGame()
                                    finish()
                                },
                                onOpenMenu = {
                                    menuStatusMsg = null
                                    showMenu = true
                                    updateEnginePaused(true)
                                },
                            )
                        }

                        if (!showMenu) {
                            if (!isReplayMode) {
                                Row(
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 16.dp, end = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isNetplay) {
                                        val speakerMask = voiceHud.remoteSpeakingMask.toInt()
                                        if (voiceListenEnabled && speakerMask != 0) {
                                            val speakingPlayers =
                                                (0 until 4)
                                                    .filter { (speakerMask and (1 shl it)) != 0 }
                                                    .joinToString("·") { "P${it + 1}" }
                                            if (speakingPlayers.isNotEmpty()) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .background(PureWhite.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        text = speakingPlayers,
                                                        color = PureBlack,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                    )
                                                }
                                            }
                                        }

                                        // Toggle Micrófono (Mute / Unmute)
                                        Box(
                                            modifier =
                                                Modifier
                                                    .background(
                                                        if (voiceMicEnabled) {
                                                            PureWhite.copy(
                                                                alpha = 0.18f,
                                                            )
                                                        } else {
                                                            CarbonSurface.copy(alpha = 0.8f)
                                                        },
                                                        RoundedCornerShape(12.dp),
                                                    ).border(
                                                        width = if (voiceHud.localSpeaking) 2.dp else 1.dp,
                                                        color =
                                                            when {
                                                                voiceHud.localSpeaking -> PureWhite
                                                                voiceMicEnabled -> SilverGray
                                                                else -> SteelBorder
                                                            },
                                                        shape = RoundedCornerShape(12.dp),
                                                    ).clickable { toggleVoiceMic() }
                                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = if (voiceMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                                contentDescription = if (voiceMicEnabled) "Silenciar micrófono" else "Activar micrófono",
                                                tint = if (voiceMicEnabled) PureWhite else SilverGray,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }

                                        // Toggle Audio de otros jugadores (Deafen / Undeafen)
                                        Box(
                                            modifier =
                                                Modifier
                                                    .background(
                                                        if (voiceListenEnabled) {
                                                            PureWhite.copy(
                                                                alpha = 0.18f,
                                                            )
                                                        } else {
                                                            CarbonSurface.copy(alpha = 0.8f)
                                                        },
                                                        RoundedCornerShape(12.dp),
                                                    ).border(
                                                        width = 1.dp,
                                                        color = if (voiceListenEnabled) SilverGray else SteelBorder,
                                                        shape = RoundedCornerShape(12.dp),
                                                    ).clickable { toggleVoiceListen() }
                                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = if (voiceListenEnabled) Icons.Default.Headset else Icons.Default.HeadsetOff,
                                                contentDescription = if (voiceListenEnabled) "Silenciar a los demás" else "Escuchar a los demás",
                                                tint = if (voiceListenEnabled) PureWhite else SilverGray,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }

                                    Box(
                                        modifier =
                                            Modifier
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
                                                }.padding(horizontal = 14.dp, vertical = 5.dp),
                                    ) {
                                        Text(
                                            text =
                                                when {
                                                    editLayoutMode -> "GUARDAR LAYOUT"
                                                    isNetplay -> {
                                                        val pingLabel = if (netplayHud.pingMs > 0u) "${netplayHud.pingMs} ms" else "-- ms"
                                                        "MENU · ${"%.0f".format(currentFps)} FPS · $pingLabel"
                                                    }
                                                    else -> "MENU · ${"%.0f".format(currentFps)} FPS"
                                                },
                                            color = if (editLayoutMode) PureWhite else SilverGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            val notice = voiceNoticeMsg
                            if (notice != null) {
                                Box(
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = if (isReplayMode) 68.dp else 52.dp, end = 20.dp)
                                            .background(CarbonSurface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                                            .border(1.dp, SteelBorder, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(text = notice, color = PureWhite, fontSize = 11.sp)
                                }
                            }
                            if (isNetplay && netplayHud.reconnecting) {
                                Box(
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 16.dp)
                                            .background(PureWhite.copy(alpha = 0.94f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = "RECONECTANDO PAR... ${netplayHud.reconnectSecsLeft}s",
                                        color = PureBlack,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                }
                            }
                        } else {
                            val scoreLabel =
                                GameSessionPolicy.scoreStatusLabel(
                                    isNetplay = isNetplay,
                                    isReplayMode = isReplayMode,
                                    scoreDef = scoreDef,
                                    rankingEligible = scoreHud.rankingEligible,
                                    creditsUsed = scoreHud.creditsUsed.toInt(),
                                    currentScore = scoreHud.currentScore.toLong(),
                                    bestSessionScore = scoreHud.bestSessionScore.toLong(),
                                )
                            InGameQuickMenu(
                                gameTitle = gameTitle,
                                fps = currentFps,
                                statusMessage = menuStatusMsg,
                                hasPhysicalController = hasPhysicalController,
                                touchConfig = touchConfig,
                                editLayoutMode = editLayoutMode,
                                isNetplay = isNetplay,
                                isReplayMode = isReplayMode,
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
                                    if (isReplayMode) {
                                        replayManuallyPaused = false
                                    }
                                    updateEnginePaused(false)
                                },
                                onExit = { finish() },
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                        ) {
                            Text(
                                text = "ERROR INICIANDO EMULADOR",
                                color = PureWhite,
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = engineError ?: "Error desconocido",
                                color = SilverGray,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            OutlinedButton(
                                onClick = { finish() },
                                shape = RectangleShape,
                                border = BorderStroke(1.dp, SteelBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SilverGray),
                            ) {
                                Text("VOLVER", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isNetplay && !netplaySynchronized && !showMenu && netplayEndReason == null && engineError == null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(ObsidianBlack.copy(alpha = 0.90f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(24.dp),
                            ) {
                                if (syncTimedOut) {
                                    Text(
                                        text = "TIEMPO DE ESPERA AGOTADO",
                                        color = PureWhite,
                                        fontFamily = SpaceGroteskFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        text = "No se pudo establecer la conexión P2P con el otro jugador tras 25 segundos.\nVerifica que ambos dispositivos estén conectados a internet y reintenta.",
                                        color = SilverGray,
                                        fontSize = 12.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            stopGame()
                                            finish()
                                        },
                                        shape = RectangleShape,
                                        border = BorderStroke(1.dp, SteelBorder),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SilverGray),
                                    ) {
                                        Text("VOLVER A LA SALA", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    CircularProgressIndicator(
                                        color = PureWhite,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Text(
                                        text = "CONECTANDO Y SINCRONIZANDO SALA (${syncSecondsElapsed}s)...",
                                        color = PureWhite,
                                        fontFamily = SpaceGroteskFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                    val targetDetail =
                                        if (netplayPeerAddrs.isNotEmpty()) {
                                            "Conectando a: ${netplayPeerAddrs.joinToString(", ")}"
                                        } else {
                                            "Estableciendo conexión P2P entre jugadores..."
                                        }
                                    Text(
                                        text = targetDetail,
                                        color = SilverGray,
                                        fontSize = 11.sp,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            stopGame()
                                            finish()
                                        },
                                        shape = RectangleShape,
                                        border = BorderStroke(1.dp, SteelBorder),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SilverGray),
                                    ) {
                                        Text("CANCELAR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    val endMsg = netplayEndReason
                    if (endMsg != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(ObsidianBlack.copy(alpha = 0.84f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text("PARTIDA FINALIZADA", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(endMsg, color = MutedGray, fontSize = 13.sp)
                                    Button(
                                        onClick = { finish() },
                                        colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
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
        val mask =
            if (port == 0) {
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
        val attrs =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        val req =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
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
        if (isFinishing) {
            if (!isReplayMode) {
                pausePlayTimer()
                flushPlayTime(force = true)
            }
            flushBestScoreOnExit()
            recordMatchHistoryOnExit()
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        val someoneSpeaking = isNetplay && voiceListenEnabled && voiceHud.remoteSpeakingMask.toInt() != 0
        setAudioDucking(audioFocusDucked || someoneSpeaking)
        if (GameSessionPolicy.shouldResumeEngine(showMenu, isReplayMode, replayManuallyPaused)) {
            updateEnginePaused(false)
        }
        if (isNetplay) {
            voiceAudioManager?.setPaused(false)
            setVoiceControls(voiceMicEnabled, voiceListenEnabled, voiceVolume, paused = false)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isReplayMode) {
            pausePlayTimer()
            flushPlayTime(force = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isReplayMode) {
            pausePlayTimer()
            flushPlayTime(force = true)
            if (isFinishing) {
                val mode =
                    if (isNetplay) {
                        if (isNetplayHost) {
                            "host"
                        } else {
                            "client"
                        }
                    } else {
                        "offline"
                    }
                AnalyticsHelper.logGameSessionEnd(
                    romSlug = gameId,
                    durationSec = 0L,
                    mode = mode,
                )
            }
        }
        if (isFinishing && isNetplay && isNetplayHost && netplayRoomId.isNotBlank()) {
            val roomIdToClose = netplayRoomId
            val appCtx = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { ArcadeRepository(appCtx).deleteRoom(roomIdToClose) }
            }
        }
        controllerManager?.stop()
        flushBestScoreOnExit()
        val exportsToAwait = synchronized(pendingExportLock) { pendingReplayExports.toList() }
        recordMatchHistoryOnExit()
        voiceAudioManager?.release()
        voiceAudioManager = null
        stopVoiceSession()
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        startupTrace?.stop()
        startupTrace = null
        NativeSurfaceBridge.detachSurface()
        if (exportsToAwait.isEmpty()) {
            stopGame()
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    exportsToAwait.forEach { it.await() }
                } finally {
                    stopGame()
                }
            }
        }
    }

    private fun syncVoiceControls() {
        setVoiceControls(
            micEnabled = voiceMicEnabled,
            listenEnabled = voiceListenEnabled,
            volume = voiceVolume,
            paused = false,
        )
        voiceAudioManager?.setCaptureEnabled(voiceMicEnabled)
        voiceAudioManager?.setPlaybackEnabled(voiceListenEnabled)
        if (!voiceListenEnabled) {
            setAudioDucking(audioFocusDucked)
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
        if (!GameSessionPolicy.shouldTrackPlayTime(isReplayMode, engineError, gameId)) return
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
        if (!GameSessionPolicy.shouldFlushPlayTime(isReplayMode, gid)) return

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
            voiceNoticeMsg =
                if (credits > 1) {
                    if (lastSubmittedSessionScore > 0L) {
                        "Récord 1CC guardado · Continue bloqueado para nuevos puntos"
                    } else {
                        "Ranking 1-Crédito desactivado ($credits créditos detectados)"
                    }
                } else {
                    "Ranking desactivado en esta sesión (Save State)"
                }
        }

        val pending = hud.pendingSubmitScore.toLong()
        if (pending <= 0L) return
        acknowledgeScoreSubmit()
        logFromAndroid(
            "INFO",
            "Score trigger recibido: score=$pending eligible=${hud.rankingEligible} credits=$credits",
        )

        if (!hud.rankingEligible || credits > 1) return
        submitOrQueueScore(score = pending, credits = credits, notifyUi = true)
    }

    private fun flushBestScoreOnExit() {
        if (isNetplay || isReplayMode || scoreDef == null) return
        val hud = runCatching { getScoreHudInfo() }.getOrNull() ?: return
        val credits = hud.creditsUsed.toInt()
        if (!hud.rankingEligible || credits > 1) return

        val candidate = maxOf(hud.pendingSubmitScore.toLong(), hud.bestSessionScore.toLong())
        if (candidate <= lastSubmittedSessionScore) return
        acknowledgeScoreSubmit()
        logFromAndroid(
            "INFO",
            "Envío de score al salir: score=$candidate credits=$credits",
        )
        submitOrQueueScore(score = candidate, credits = credits, notifyUi = false)
    }

    private fun recordMatchHistoryOnExit() {
        if (matchHistoryRecorded || isReplayMode || engineError != null) return
        if (isNetplay && roomIsPrivate) return
        val gid = gameId
        if (gid.isBlank() || matchSessionId.isBlank()) return

        val totalSeconds =
            synchronized(playTimerLock) {
                (accumulatedActiveMs / 1000L).toInt()
            }
        if (totalSeconds < 5) return
        matchHistoryRecorded = true

        val mode = if (isNetplay) netplayMode else "local"
        val roomId = if (isNetplay && netplayRoomId.isNotBlank()) netplayRoomId else null
        val startedIso =
            matchStartedAtIso.ifBlank {
                java.time.Instant
                    .now()
                    .toString()
            }
        val endedIso =
            java.time.Instant
                .now()
                .toString()
        val latestSessionBest = runCatching { getScoreHudInfo().bestSessionScore.toLong() }.getOrDefault(0L)
        val bestScore =
            if (!isNetplay) {
                maxOf(lastSubmittedSessionScore, scoreHud.bestSessionScore.toLong(), latestSessionBest).takeIf { it > 0L }
            } else {
                null
            }
        val playersJsonStr = roomPlayersJson
        val appCtx = applicationContext
        val sessionId = matchSessionId
        val initialPlayers =
            if (isNetplay) {
                runCatching {
                    jsonFormat.decodeFromString<List<MatchHistoryPlayerDto>>(playersJsonStr)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

        val repo = ArcadeRepository(appCtx)
        val uid = repo.currentUserId() ?: return

        // Write-ahead enqueue so match history is never lost if offline or killed mid-exit
        val pendingEntry =
            PendingMatchHistoryEntry(
                userId = uid,
                clientSessionId = sessionId,
                gameId = gid,
                mode = mode,
                roomId = roomId,
                isPrivate = false,
                players = initialPlayers,
                startedAtIso = startedIso,
                endedAtIso = endedIso,
                durationSeconds = totalSeconds,
                score = bestScore,
            )
        PendingMatchHistoryQueue.enqueue(appCtx, pendingEntry)

        val syncJob =
            CoroutineScope(Dispatchers.IO).launch {
                val players =
                    if (isNetplay) {
                        initialPlayers
                    } else {
                        val username = runCatching { repo.fetchMyProfile()?.username }.getOrNull() ?: "Jugador"
                        listOf(MatchHistoryPlayerDto(userId = uid, username = username, slot = 0))
                    }
                val result =
                    runCatching {
                        repo.recordMatchHistory(
                            clientSessionId = sessionId,
                            gameId = gid,
                            mode = mode,
                            roomId = roomId,
                            isPrivate = false,
                            players = players,
                            startedAtIso = startedIso,
                            endedAtIso = endedIso,
                            durationSeconds = totalSeconds,
                            score = bestScore,
                        )
                    }
                if (result.isSuccess) {
                    PendingMatchHistoryQueue.removeBySessionId(appCtx, sessionId, uid)
                }
            }
        ExitSyncCoordinator.register(syncJob)
    }

    private fun submitOrQueueScore(
        score: Long,
        credits: Int,
        notifyUi: Boolean,
    ) {
        if (score <= 0L || gameId.isBlank() || romSha256.isBlank()) return
        if (score <= lastSubmittedSessionScore) return
        lastSubmittedSessionScore = score

        val appCtx = applicationContext
        val gid = gameId
        val sha = romSha256
        val pendingDir = File(filesDir, "pending_replays")
        val replayFile =
            File(
                pendingDir,
                "${gid}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.bsv.gz",
            )
        val exportDone = CompletableDeferred<Unit>()
        synchronized(pendingExportLock) {
            pendingReplayExports.add(exportDone)
        }

        val scoreJob =
            CoroutineScope(Dispatchers.IO).launch {
                val exportedReplay =
                    try {
                        runCatching {
                            pendingDir.mkdirs()
                            exportRecordedReplay(replayFile.absolutePath)
                            replayFile.takeIf { it.exists() && it.length() > 0L }
                        }.getOrNull()
                    } finally {
                        synchronized(pendingExportLock) {
                            pendingReplayExports.remove(exportDone)
                        }
                        exportDone.complete(Unit)
                    }

                if (exportedReplay == null) {
                    logFromAndroid("WARN", "exportRecordedReplay vacío o fallido para score=$score; omitiendo envío")
                    return@launch
                }

                val repo = ArcadeRepository(appCtx)
                val uid = repo.currentUserId()
                if (uid.isNullOrBlank()) {
                    exportedReplay.delete()
                    return@launch
                }

                val rankResult = runCatching { repo.fetchMyGameRank(gid) }
                if (rankResult.isSuccess) {
                    val currentBest = rankResult.getOrNull()?.score
                    if (currentBest != null && score < currentBest) {
                        exportedReplay.delete()
                        logFromAndroid("INFO", "Score $score < mejor online ($currentBest); replay descartado")
                        return@launch
                    }
                }

                val uploadedReplay = runCatching { repo.uploadReplay(gid, exportedReplay) }.getOrNull()
                if (uploadedReplay == null) {
                    logFromAndroid("WARN", "uploadReplay falló transitoriamente, encolando offline score=$score")
                    PendingScoreQueue.enqueue(
                        appCtx,
                        PendingScoreSubmission(
                            userId = uid,
                            gameId = gid,
                            score = score,
                            romSha256 = sha,
                            creditsUsed = credits,
                            isHardcore = true,
                            replayLocalPath = exportedReplay.absolutePath,
                        ),
                    )
                    if (notifyUi && !isFinishing && !isDestroyed) {
                        voiceNoticeMsg = "Récord guardado offline ($score)"
                    }
                    return@launch
                }

                val result =
                    runCatching {
                        repo.submitHighScore(
                            gameId = gid,
                            score = score,
                            romSha256 = sha,
                            creditsUsed = credits,
                            isHardcore = true,
                            replayPath = uploadedReplay.first,
                            replaySizeBytes = uploadedReplay.second,
                            replaySha256 = uploadedReplay.third,
                        )
                    }
                val dto = result.getOrNull()
                if (result.isSuccess && dto != null) {
                    if (dto.accepted || PendingScoreQueue.isPermanentRejection(dto.reason)) {
                        exportedReplay.delete()
                    } else {
                        PendingScoreQueue.enqueue(
                            appCtx,
                            PendingScoreSubmission(
                                userId = uid,
                                gameId = gid,
                                score = score,
                                romSha256 = sha,
                                creditsUsed = credits,
                                isHardcore = true,
                                replayLocalPath = exportedReplay.absolutePath,
                            ),
                        )
                    }
                    logFromAndroid(
                        "INFO",
                        "submit_high_score resp: accepted=${dto.accepted} new=${dto.isNewRecord} rank=${dto.rank} reason=${dto.reason}",
                    )
                    if (!dto.accepted) {
                        Log.w("BaldDogScore", "Score rechazado por servidor: ${dto.reason}")
                    }
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "unknown"
                    logFromAndroid("WARN", "submit_high_score falló ($errMsg), encolando offline")
                    PendingScoreQueue.enqueue(
                        appCtx,
                        PendingScoreSubmission(
                            userId = uid,
                            gameId = gid,
                            score = score,
                            romSha256 = sha,
                            creditsUsed = credits,
                            isHardcore = true,
                            replayLocalPath = exportedReplay.absolutePath,
                        ),
                    )
                    if (notifyUi && !isFinishing && !isDestroyed) {
                        voiceNoticeMsg = "Récord guardado offline ($score)"
                    }
                }
            }
        ExitSyncCoordinator.register(scoreJob)
    }

    private fun hasLegacyWriteStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureLegacyWriteStoragePermission(onGranted: () -> Unit): Boolean {
        if (hasLegacyWriteStoragePermission()) {
            return true
        }
        pendingStorageExportAction = onGranted
        writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return false
    }

    private fun captureAndSaveScreenshot(title: String) {
        if (!ensureLegacyWriteStoragePermission { captureAndSaveScreenshot(title) }) return
        val appCtx = applicationContext
        val tmpPng = File(cacheDir, "screenshots/shot_${System.currentTimeMillis()}.png")
        lifecycleScope.launch(Dispatchers.IO) {
            val captured =
                try {
                    tmpPng.parentFile?.mkdirs()
                    captureScreenshotPng(tmpPng.absolutePath)
                    MediaExportHelper.savePngToGallery(appCtx, tmpPng, title)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                } finally {
                    tmpPng.delete()
                }
            if (coroutineContext.isActive && !isFinishing && !isDestroyed) {
                voiceNoticeMsg =
                    if (captured.isSuccess) {
                        "Captura guardada: ${captured.getOrNull()}"
                    } else {
                        "Error al capturar pantalla"
                    }
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
        if (!ensureLegacyWriteStoragePermission {
                exportReplayVideoClip(romPath, systemDir, saveDir, title)
            }
        ) {
            return
        }
        exportProgressPct = 0
        val appCtx = applicationContext
        val nativeDir = applicationInfo.nativeLibraryDir
        val rPath = replayPath
        val sDef = scoreDef
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stopGame()
                val res =
                    ReplayVideoExporter.exportToGallery(
                        context = appCtx,
                        nativeLibDir = nativeDir,
                        romPath = romPath,
                        systemDir = systemDir,
                        saveDir = saveDir,
                        replayPath = rPath,
                        gameTitle = title,
                        onProgress = { pct ->
                            if (coroutineContext.isActive && !isFinishing && !isDestroyed) {
                                exportProgressPct = pct
                            }
                        },
                    )
                if (coroutineContext.isActive && !isFinishing && !isDestroyed) {
                    exportProgressPct = null
                    replayManuallyPaused = false
                    runCatching {
                        startReplayGame(
                            nativeLibDir = nativeDir,
                            romPath = romPath,
                            systemDir = systemDir,
                            saveDir = saveDir,
                            replayPath = rPath,
                            scoreDef = sDef,
                        )
                    }
                    voiceNoticeMsg =
                        if (res.isSuccess) {
                            "Clip MP4 guardado en Movies/balddog ARCADE"
                        } else {
                            "Error exportando MP4: ${res.exceptionOrNull()?.message ?: "desconocido"}"
                        }
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    exportProgressPct = null
                }
            }
        }
    }
}

internal data class AudioFocusAction(
    val audioFocusDucked: Boolean? = null,
    val setDucking: Boolean? = null,
    val setPaused: Boolean? = null,
)

internal object GameSessionPolicy {
    fun resolveAudioFocusChange(
        focusChange: Int,
        isNetplay: Boolean,
        showMenu: Boolean,
        isReplayMode: Boolean,
        replayManuallyPaused: Boolean,
        voiceListenEnabled: Boolean,
        remoteSpeakingMask: Int,
    ): AudioFocusAction =
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                AudioFocusAction(
                    audioFocusDucked = true,
                    setDucking = true,
                    setPaused = null,
                )

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                if (isNetplay) {
                    AudioFocusAction(
                        audioFocusDucked = true,
                        setDucking = true,
                        setPaused = null,
                    )
                } else {
                    AudioFocusAction(
                        audioFocusDucked = false,
                        setDucking = false,
                        setPaused = true,
                    )
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                val someoneSpeaking = isNetplay && voiceListenEnabled && remoteSpeakingMask != 0
                val resumeEngine = shouldResumeEngine(showMenu, isReplayMode, replayManuallyPaused)
                AudioFocusAction(
                    audioFocusDucked = false,
                    setDucking = someoneSpeaking,
                    setPaused = if (resumeEngine) false else null,
                )
            }

            else -> AudioFocusAction()
        }

    fun shouldResumeEngine(
        showMenu: Boolean,
        isReplayMode: Boolean,
        replayManuallyPaused: Boolean,
    ): Boolean = !showMenu && !(isReplayMode && replayManuallyPaused)

    fun shouldTrackPlayTime(
        isReplayMode: Boolean,
        engineError: String?,
        gameId: String,
    ): Boolean = !isReplayMode && engineError == null && gameId.isNotBlank()

    fun shouldFlushPlayTime(
        isReplayMode: Boolean,
        gameId: String,
    ): Boolean = !isReplayMode && gameId.isNotBlank()

    fun scoreStatusLabel(
        isNetplay: Boolean,
        isReplayMode: Boolean,
        scoreDef: String?,
        rankingEligible: Boolean,
        creditsUsed: Int,
        currentScore: Long,
        bestSessionScore: Long,
    ): String? =
        when {
            isNetplay || isReplayMode -> null
            scoreDef == null -> "Ranking: Sin configuración para este juego"
            creditsUsed > 1 -> "Ranking: Desactivado ($creditsUsed créditos)"
            !rankingEligible -> "Ranking: Desactivado (Save State)"
            else -> "Ranking: 1 Crédito · Score $currentScore (Máx $bestSessionScore)"
        }
}
