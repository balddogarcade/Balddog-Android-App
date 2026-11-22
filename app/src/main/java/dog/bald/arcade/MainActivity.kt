package dog.bald.arcade

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.IpfsRomManager
import dog.bald.arcade.data.PendingMatchHistoryQueue
import dog.bald.arcade.data.PendingScoreQueue
import dog.bald.arcade.net.LanNsdManager
import dog.bald.arcade.ui.components.AppPreloadScreen
import dog.bald.arcade.ui.screens.AuthScreen
import dog.bald.arcade.ui.screens.MainShellScreen
import dog.bald.arcade.ui.theme.BaldDogArcadeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    @Volatile
    private var activeHostRoomId: String? = null

    override fun onDestroy() {
        super.onDestroy()
        val roomId = activeHostRoomId
        if (!roomId.isNullOrBlank()) {
            activeHostRoomId = null
            val appCtx = applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { ArcadeRepository(appCtx).deleteRoom(roomId) }
            }
        }
    }

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

                LaunchedEffect(isAuthenticated) {
                    if (isAuthenticated) {
                        PendingScoreQueue.flushAll(this@MainActivity, repo)
                        PendingMatchHistoryQueue.flushAll(this@MainActivity, repo)
                    }
                }

                when {
                    checkingSession -> {
                        AppPreloadScreen(message = "Iniciando Bald Dog...")
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
                            onActiveHostRoomChanged = { roomId -> activeHostRoomId = roomId },
                            onSignedOut = { isAuthenticated = false },
                            onLaunchLocalRom = { game ->
                                val romFile = File(romsDir, game.romFilename)
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    putExtra(GameActivity.EXTRA_GAME_ID, game.id)
                                    putExtra(GameActivity.EXTRA_ROM_PATH, romFile.absolutePath)
                                    putExtra(GameActivity.EXTRA_SYSTEM_DIR, systemDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_SAVE_DIR, saveDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_GAME_TITLE, game.title)
                                    putExtra(GameActivity.EXTRA_ROM_SHA256, game.sha256)
                                    game.scoreConfig?.lboard?.takeIf { it.isNotBlank() }?.let { lboard ->
                                        putExtra(GameActivity.EXTRA_SCORE_DEF, lboard)
                                    }
                                }
                                startActivity(intent)
                            },
                            onLaunchReplay = { game, replayFile, entry ->
                                val romFile = File(romsDir, game.romFilename)
                                val intent = Intent(this@MainActivity, GameActivity::class.java).apply {
                                    putExtra(GameActivity.EXTRA_GAME_ID, game.id)
                                    putExtra(GameActivity.EXTRA_ROM_PATH, romFile.absolutePath)
                                    putExtra(GameActivity.EXTRA_SYSTEM_DIR, systemDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_SAVE_DIR, saveDir.absolutePath)
                                    putExtra(GameActivity.EXTRA_GAME_TITLE, game.title)
                                    putExtra(GameActivity.EXTRA_ROM_SHA256, game.sha256)
                                    putExtra(GameActivity.EXTRA_REPLAY_PATH, replayFile.absolutePath)
                                    putExtra(GameActivity.EXTRA_REPLAY_PLAYER_NAME, entry.username)
                                    putExtra(GameActivity.EXTRA_REPLAY_TARGET_SCORE, entry.score)
                                    game.scoreConfig?.lboard?.takeIf { it.isNotBlank() }?.let { lboard ->
                                        putExtra(GameActivity.EXTRA_SCORE_DEF, lboard)
                                    }
                                }
                                startActivity(intent)
                            },
                            onLaunchNetplayMatch = { game, room, mySlot, localPort, voiceLocalPort ->
                                val romFile = File(romsDir, game.romFilename)
                                val peers = room.slotsJson.entries
                                    .filter { it.key.toIntOrNull() != mySlot }
                                    .sortedBy { it.key.toIntOrNull() ?: 0 }
                                val peerHandles = peers.mapNotNull { it.key.toIntOrNull() }.toIntArray()
                                val peerAddrs = ArrayList(peers.map { it.value.endpoint })
                                val voicePeers = peers.filter { !it.value.voiceEndpoint.isNullOrBlank() }
                                val voicePeerHandles = voicePeers.mapNotNull { it.key.toIntOrNull() }.toIntArray()
                                val voicePeerAddrs = ArrayList(voicePeers.mapNotNull { it.value.voiceEndpoint })
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
                                    putExtra(GameActivity.EXTRA_NETPLAY_MODE, room.mode)
                                    putExtra(GameActivity.EXTRA_ROOM_IS_PRIVATE, room.isPrivate)
                                    val playersJson = kotlinx.serialization.json.buildJsonArray {
                                        room.slotsJson.entries
                                            .sortedBy { it.key.toIntOrNull() ?: 0 }
                                            .forEach { (slotKey, slotInfo) ->
                                                add(
                                                    kotlinx.serialization.json.buildJsonObject {
                                                        put("user_id", kotlinx.serialization.json.JsonPrimitive(slotInfo.userId))
                                                        put("username", kotlinx.serialization.json.JsonPrimitive(slotInfo.username))
                                                        put("slot", kotlinx.serialization.json.JsonPrimitive(slotKey.toIntOrNull() ?: 0))
                                                    }
                                                )
                                            }
                                    }.toString()
                                    putExtra(GameActivity.EXTRA_ROOM_PLAYERS_JSON, playersJson)
                                    putExtra(GameActivity.EXTRA_IS_HOST, room.hostId == repo.currentUserId())
                                    putExtra(GameActivity.EXTRA_VOICE_LOCAL_PORT, voiceLocalPort)
                                    putExtra(GameActivity.EXTRA_VOICE_PEER_HANDLES, voicePeerHandles)
                                    putStringArrayListExtra(GameActivity.EXTRA_VOICE_PEER_ADDRS, voicePeerAddrs)
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
