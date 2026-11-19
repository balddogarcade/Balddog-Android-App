package dog.bald.arcade

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.IpfsRomManager
import dog.bald.arcade.ui.screens.AuthScreen
import dog.bald.arcade.ui.screens.MainShellScreen
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.BaldDogArcadeTheme
import dog.bald.arcade.ui.theme.CoinYellow
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repo = ArcadeRepository(applicationContext)
        val romManager = IpfsRomManager(applicationContext)
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val romsDir = File(filesDir, "roms").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        val saveDir = File(filesDir, "saves").apply { mkdirs() }

        setContent {
            BaldDogArcadeTheme {
                var checkingSession by remember { mutableStateOf(true) }
                var isAuthenticated by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    isAuthenticated = repo.restoreSession()
                    checkingSession = false
                }

                when {
                    checkingSession -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ArcadeBlack),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = CoinYellow)
                        }
                    }

                    !isAuthenticated -> {
                        AuthScreen(
                            repo = repo,
                            onAuthenticated = { isAuthenticated = true }
                        )
                    }

                    else -> {
                        MainShellScreen(
                            repo = repo,
                            romManager = romManager,
                            nativeLibDir = nativeLibDir,
                            onSignedOut = { isAuthenticated = false },
                            onLaunchLocalRom = { game ->
                                val romFile = File(romsDir, game.romFilename)
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    putExtra(GameActivity.EXTRA_ROM_PATH, romFile.absolutePath)
                                    putExtra(GameActivity.EXTRA_SYSTEM_DIR, systemDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_SAVE_DIR, saveDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_GAME_TITLE, game.title)
                                }
                                startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}
