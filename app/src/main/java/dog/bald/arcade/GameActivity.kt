package dog.bald.arcade

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dog.bald.arcade.engine.NativeSurfaceBridge
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.BaldDogArcadeTheme
import uniffi.balddog_android.setGamePaused
import uniffi.balddog_android.startGame
import uniffi.balddog_android.stopGame

class GameActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_SYSTEM_DIR = "system_dir"
        const val EXTRA_SAVE_DIR = "save_dir"
        const val EXTRA_GAME_TITLE = "game_title"
    }

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var engineError by mutableStateOf<String?>(null)

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setGamePaused(true)
            AudioManager.AUDIOFOCUS_GAIN -> setGamePaused(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH).orEmpty()
        val systemDir = intent.getStringExtra(EXTRA_SYSTEM_DIR).orEmpty()
        val saveDir = intent.getStringExtra(EXTRA_SAVE_DIR).orEmpty()

        requestAudioFocus()

        try {
            if (romPath.isBlank()) {
                engineError = "No se especificó ruta de ROM"
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ArcadeBlack)
                ) {
                    if (engineError == null) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            val rect = holder.surfaceFrame
                                            NativeSurfaceBridge.attachSurface(
                                                holder.surface,
                                                rect.width().coerceAtLeast(1),
                                                rect.height().coerceAtLeast(1)
                                            )
                                        }

                                        override fun surfaceChanged(
                                            holder: SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int
                                        ) {
                                            NativeSurfaceBridge.resizeSurface(width, height)
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            NativeSurfaceBridge.detachSurface()
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
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
        setGamePaused(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        NativeSurfaceBridge.detachSurface()
        stopGame()
    }
}
