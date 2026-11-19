package dog.bald.arcade

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dog.bald.arcade.engine.NativeSurfaceBridge
import dog.bald.arcade.input.ControllerManager
import dog.bald.arcade.input.TouchLayoutConfig
import dog.bald.arcade.input.TouchLayoutPrefs
import dog.bald.arcade.ui.game.ArcadeTouchOverlay
import dog.bald.arcade.ui.game.InGameQuickMenu
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeMagenta
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.BaldDogArcadeTheme
import dog.bald.arcade.ui.theme.CoinYellow
import dog.bald.arcade.ui.theme.LeatherBlack
import dog.bald.arcade.ui.theme.RetroIvory
import kotlinx.coroutines.delay
import uniffi.balddog_android.NetplayHudInfo
import uniffi.balddog_android.getCurrentFps
import uniffi.balddog_android.getNetplayHudStats
import uniffi.balddog_android.loadGameState
import uniffi.balddog_android.saveGameState
import uniffi.balddog_android.setGamePaused
import uniffi.balddog_android.setPlayerInput
import uniffi.balddog_android.startGame
import uniffi.balddog_android.startNetplayGame
import uniffi.balddog_android.stopGame
import java.io.File

class GameActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_SYSTEM_DIR = "system_dir"
        const val EXTRA_SAVE_DIR = "save_dir"
        const val EXTRA_GAME_TITLE = "game_title"
        const val EXTRA_NETPLAY_ENABLED = "netplay_enabled"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_LOCAL_HANDLE = "local_handle"
        const val EXTRA_NUM_PLAYERS = "num_players"
        const val EXTRA_PEER_HANDLES = "peer_handles"
        const val EXTRA_PEER_ADDRS = "peer_addrs"
        const val EXTRA_INPUT_DELAY = "input_delay"
    }

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var controllerManager: ControllerManager? = null

    private var engineError by mutableStateOf<String?>(null)
    private var showMenu by mutableStateOf(false)
    private var editLayoutMode by mutableStateOf(false)
    private var hasPhysicalController by mutableStateOf(false)
    private var touchConfig by mutableStateOf(TouchLayoutConfig())
    private var menuStatusMsg by mutableStateOf<String?>(null)
    private var currentFps by mutableFloatStateOf(0f)
    private var isNetplay by mutableStateOf(false)
    private var netplayHud by mutableStateOf(NetplayHudInfo(0u, 0u, false, 0u))

    private var touchMaskP1: Int = 0
    private val physicalMasks = IntArray(4)

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setGamePaused(true)
            AudioManager.AUDIOFOCUS_GAIN -> if (!showMenu) setGamePaused(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        touchConfig = TouchLayoutPrefs.load(this)

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

        try {
            if (romPath.isBlank()) {
                engineError = "No se especificó ruta de ROM"
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
            } else {
                startGame(
                    nativeLibDir = applicationInfo.nativeLibraryDir,
                    romPath = romPath,
                    systemDir = systemDir,
                    saveDir = saveDir
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
                        }
                        delay(500)
                    }
                }

                BackHandler {
                    if (editLayoutMode) {
                        editLayoutMode = false
                        TouchLayoutPrefs.save(this@GameActivity, touchConfig)
                    } else {
                        showMenu = !showMenu
                        setGamePaused(showMenu)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ArcadeBlack)
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

                        val shouldShowTouch = !hasPhysicalController || touchConfig.forceShowTouch || editLayoutMode
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

                        if (!showMenu) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp)
                                    .background(LeatherBlack.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                                    .border(1.dp, if (editLayoutMode) CoinYellow else ArcadeMagenta.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (editLayoutMode) {
                                            editLayoutMode = false
                                            TouchLayoutPrefs.save(this@GameActivity, touchConfig)
                                        } else {
                                            menuStatusMsg = null
                                            showMenu = true
                                            setGamePaused(true)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = when {
                                        editLayoutMode -> "GUARDAR LAYOUT"
                                        isNetplay -> "MENU · ${"%.0f".format(currentFps)} FPS · ${netplayHud.pingMs} ms"
                                        else -> "MENU · ${"%.0f".format(currentFps)} FPS"
                                    },
                                    color = if (editLayoutMode) CoinYellow else RetroIvory,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (isNetplay && netplayHud.reconnecting) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 42.dp)
                                        .background(ArcadeOrange.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "RECONECTANDO PAR... ${netplayHud.reconnectSecsLeft}s",
                                        color = ArcadeBlack,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        } else {
                            InGameQuickMenu(
                                gameTitle = gameTitle,
                                fps = currentFps,
                                statusMessage = menuStatusMsg,
                                hasPhysicalController = hasPhysicalController,
                                touchConfig = touchConfig,
                                editLayoutMode = editLayoutMode,
                                onTouchConfigChange = { updated ->
                                    touchConfig = updated
                                    TouchLayoutPrefs.save(this@GameActivity, updated)
                                },
                                onToggleEditLayout = {
                                    editLayoutMode = true
                                    showMenu = false
                                    setGamePaused(false)
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
                                    setGamePaused(false)
                                },
                                onExit = { finish() }
                            )
                        }
                    } else {
                        Text(
                            text = engineError ?: "",
                            color = ArcadeOrange,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
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
        setGamePaused(true)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        if (!showMenu) {
            setGamePaused(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerManager?.stop()
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        NativeSurfaceBridge.detachSurface()
        stopGame()
    }
}
