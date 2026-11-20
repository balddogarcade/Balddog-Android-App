package dog.bald.arcade

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import dog.bald.arcade.net.LanNsdManager
import dog.bald.arcade.ui.screens.AuthScreen
import dog.bald.arcade.ui.screens.MainShellScreen
import dog.bald.arcade.ui.theme.BaldDogArcadeTheme
import dog.bald.arcade.ui.theme.ObsidianBlack
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val repo = ArcadeRepository(applicationContext)
        val romManager = IpfsRomManager(applicationContext)
        val lanNsd = LanNsdManager(applicationContext)
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
                                .background(ObsidianBlack),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = dog.bald.arcade.ui.theme.PureWhite)
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
                            lanNsd = lanNsd,
                            nativeLibDir = nativeLibDir,
                            onSignedOut = { isAuthenticated = false },
                            onLaunchLocalRom = { game ->
                                val romFile = File(romsDir, game.romFilename)
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    putExtra(GameActivity.EXTRA_GAME_ID, game.id)
                                    putExtra(GameActivity.EXTRA_ROM_PATH, romFile.absolutePath)
                                    putExtra(GameActivity.EXTRA_SYSTEM_DIR, systemDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_SAVE_DIR, saveDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_GAME_TITLE, game.title)
                                }
                                startActivity(intent)
                            },
                            onLaunchNetplayMatch = { game, room, mySlot, localPort ->
                                val romFile = File(romsDir, game.romFilename)
                                val peers = room.slotsJson.entries
                                    .filter { it.key.toIntOrNull() != mySlot }
                                    .sortedBy { it.key.toIntOrNull() ?: 0 }
                                val peerHandles = peers.mapNotNull { it.key.toIntOrNull() }.toIntArray()
                                val peerAddrs = ArrayList(peers.map { it.value.endpoint })
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    putExtra(GameActivity.EXTRA_GAME_ID, game.id)
                                    putExtra(GameActivity.EXTRA_ROM_PATH, romFile.absolutePath)
                                    putExtra(GameActivity.EXTRA_SYSTEM_DIR, systemDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_SAVE_DIR, saveDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_GAME_TITLE, game.title)
                                    putExtra(GameActivity.EXTRA_NETPLAY_ENABLED, true)
                                    putExtra(GameActivity.EXTRA_LOCAL_PORT, localPort)
                                    putExtra(GameActivity.EXTRA_LOCAL_HANDLE, mySlot)
                                    putExtra(GameActivity.EXTRA_NUM_PLAYERS, room.slotsJson.size.coerceAtLeast(2))
                                    putExtra(GameActivity.EXTRA_PEER_HANDLES, peerHandles)
                                    putStringArrayListExtra(GameActivity.EXTRA_PEER_ADDRS, peerAddrs)
                                    putExtra(GameActivity.EXTRA_INPUT_DELAY, if (room.mode == "lan") 1 else 2)
                                    putExtra(GameActivity.EXTRA_ROOM_ID, room.id)
                                    putExtra(GameActivity.EXTRA_IS_HOST, room.hostId == repo.currentUserId())
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
