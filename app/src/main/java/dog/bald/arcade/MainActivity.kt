package dog.bald.arcade

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    @Volatile
    private var activeHostRoomId: String? = null

    private var pendingDeepLinkRoomId by mutableStateOf<String?>(null)

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            val roomId = activeHostRoomId
            if (!roomId.isNullOrBlank()) {
                activeHostRoomId = null
                val appCtx = applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { ArcadeRepository(appCtx).deleteRoom(roomId) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ArcadeRepository(applicationContext).handleAuthDeeplink(intent)
        dog.bald.arcade.net.MatchmakingPolicy.parseRoomIdFromUri(intent.dataString)?.let { roomId ->
            dog.bald.arcade.diagnostics.CrashlyticsHelper.logDeepLinkEvent("onNewIntent", "roomId=$roomId")
            pendingDeepLinkRoomId = roomId
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val repo = ArcadeRepository(applicationContext)
        repo.handleAuthDeeplink(intent)
        dog.bald.arcade.net.MatchmakingPolicy.parseRoomIdFromUri(intent?.dataString)?.let { roomId ->
            dog.bald.arcade.diagnostics.CrashlyticsHelper.logDeepLinkEvent("onCreate", "roomId=$roomId")
            pendingDeepLinkRoomId = roomId
        }
        val isResetPasswordLink = intent?.data?.host == "reset-password"
        val romManager = IpfsRomManager(applicationContext)
        val lanNsd = LanNsdManager(applicationContext)
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val romsDir = File(filesDir, "roms").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        val saveDir = File(filesDir, "saves").apply { mkdirs() }

        setContent {
            BaldDogArcadeTheme {
                val sessionStatus by repo.sessionStatus.collectAsState()
                var lastAuthenticatedUserId by remember { mutableStateOf<String?>(null) }
                var gameReturnCounter by remember { mutableIntStateOf(0) }

                val gameActivityLauncher =
                    rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult(),
                    ) {
                        gameReturnCounter++
                    }

                LaunchedEffect(sessionStatus) {
                    when (val status = sessionStatus) {
                        is SessionStatus.Authenticated -> {
                            val uid = status.session.user?.id ?: repo.currentUserId()
                            if (!uid.isNullOrBlank()) {
                                lastAuthenticatedUserId = uid
                            }
                            PendingScoreQueue.flushAll(this@MainActivity, repo)
                            PendingMatchHistoryQueue.flushAll(this@MainActivity, repo)
                        }
                        is SessionStatus.NotAuthenticated -> {
                            lastAuthenticatedUserId = null
                        }
                        else -> Unit
                    }
                }

                val activeUserId =
                    when (val status = sessionStatus) {
                        is SessionStatus.Authenticated -> status.session.user?.id ?: repo.currentUserId() ?: lastAuthenticatedUserId
                        is SessionStatus.RefreshFailure -> lastAuthenticatedUserId ?: repo.currentUserId()
                        else -> null
                    }

                when {
                    sessionStatus is SessionStatus.Initializing -> {
                        AppPreloadScreen(message = "Iniciando Bald Dog...")
                    }

                    activeUserId == null -> {
                        AuthScreen(
                            repo = repo,
                            onAuthenticated = {
                                repo.currentUserId()?.let { uid ->
                                    lastAuthenticatedUserId = uid
                                }
                            },
                        )
                    }

                    else -> {
                        key(activeUserId) {
                            MainShellScreen(
                                repo = repo,
                                romManager = romManager,
                                lanNsd = lanNsd,
                                nativeLibDir = nativeLibDir,
                                currentUserId = activeUserId,
                                gameReturnCounter = gameReturnCounter,
                                pendingDeepLinkRoomId = pendingDeepLinkRoomId,
                                onConsumePendingDeepLinkRoomId = { pendingDeepLinkRoomId = null },
                                onActiveHostRoomChanged = { roomId -> activeHostRoomId = roomId },
                                onSignedOut = { lastAuthenticatedUserId = null },
                                onLaunchLocalRom = { game ->
                                    val romFile = File(romsDir, game.romFilename)
                                    val intent =
                                        Intent(this@MainActivity, GameActivity::class.java).apply {
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
                                    gameActivityLauncher.launch(intent)
                                },
                                onLaunchReplay = { game, replayFile, entry ->
                                    val romFile = File(romsDir, game.romFilename)
                                    val intent =
                                        Intent(this@MainActivity, GameActivity::class.java).apply {
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
                                    gameActivityLauncher.launch(intent)
                                },
                                onLaunchNetplayMatch = { game, room, mySlot, localPort, voiceLocalPort ->
                                    val romFile = File(romsDir, game.romFilename)
                                    // Mapear slots ocupados en orden ascendente a handles contiguos (0..N-1) para GGRS y Libretro
                                    val mySlotEndpoint = room.slotsJson[mySlot.toString()]?.endpoint
                                    val myPublicIp = mySlotEndpoint?.substringBefore('|')?.substringBefore(':')

                                    val seatedEntries =
                                        room.slotsJson.entries
                                            .mapNotNull { entry -> entry.key.toIntOrNull()?.let { slotIdx -> slotIdx to entry.value } }
                                            .sortedBy { it.first }
                                    val totalPlayers = seatedEntries.size.coerceAtLeast(2)
                                    val myPlayerIndex = seatedEntries.indexOfFirst { it.first == mySlot }.takeIf { it >= 0 } ?: 0

                                    val peerHandles = mutableListOf<Int>()
                                    val peerAddrs = arrayListOf<String>()
                                    seatedEntries.forEachIndexed { handle, (_, slotInfo) ->
                                        if (handle != myPlayerIndex) {
                                            peerHandles.add(handle)
                                            val effectiveEp =
                                                dog.bald.arcade.net.NetplayEndpointResolver.resolveEffectiveEndpoint(
                                                    slotInfo.endpoint,
                                                    myPublicIp,
                                                )
                                            peerAddrs.add(effectiveEp)
                                        }
                                    }

                                    val voicePeerHandles = mutableListOf<Int>()
                                    val voicePeerAddrs = arrayListOf<String>()
                                    seatedEntries.forEachIndexed { handle, (_, slotInfo) ->
                                        if (handle != myPlayerIndex && !slotInfo.voiceEndpoint.isNullOrBlank()) {
                                            voicePeerHandles.add(handle)
                                            val effectiveVoiceEp =
                                                dog.bald.arcade.net.NetplayEndpointResolver.resolveEffectiveEndpoint(
                                                    slotInfo.voiceEndpoint!!,
                                                    myPublicIp,
                                                )
                                            voicePeerAddrs.add(effectiveVoiceEp)
                                        }
                                    }

                                    android.util.Log.i(
                                        "BaldDogNetplay",
                                        "onLaunchNetplayMatch: room=${room.id}, mode=${room.mode}, mySlot=$mySlot, myPlayerIndex=$myPlayerIndex, totalPlayers=$totalPlayers, peerHandles=$peerHandles, peerAddrs=$peerAddrs, localPort=$localPort",
                                    )

                                    val intent =
                                        Intent(this@MainActivity, GameActivity::class.java).apply {
                                            putExtra(GameActivity.EXTRA_GAME_ID, game.id)
                                            putExtra(GameActivity.EXTRA_ROM_PATH, romFile.absolutePath)
                                            putExtra(GameActivity.EXTRA_SYSTEM_DIR, systemDir.absolutePath)
                                            putExtra(GameActivity.EXTRA_SAVE_DIR, saveDir.absolutePath)
                                            putExtra(GameActivity.EXTRA_GAME_TITLE, game.title)
                                            putExtra(GameActivity.EXTRA_NETPLAY_ENABLED, true)
                                            putExtra(GameActivity.EXTRA_LOCAL_PORT, localPort)
                                            putExtra(GameActivity.EXTRA_LOCAL_HANDLE, myPlayerIndex)
                                            putExtra(GameActivity.EXTRA_NUM_PLAYERS, totalPlayers)
                                            putExtra(GameActivity.EXTRA_PEER_HANDLES, peerHandles.toIntArray())
                                            putStringArrayListExtra(GameActivity.EXTRA_PEER_ADDRS, peerAddrs)
                                            putExtra(GameActivity.EXTRA_INPUT_DELAY, if (room.mode == "lan") 1 else 2)
                                            putExtra(GameActivity.EXTRA_ROOM_ID, room.id)
                                            putExtra(GameActivity.EXTRA_NETPLAY_MODE, room.mode)
                                            putExtra(GameActivity.EXTRA_ROOM_IS_PRIVATE, room.isPrivate)
                                            val playersJson =
                                                kotlinx.serialization.json
                                                    .buildJsonArray {
                                                        room.slotsJson.entries
                                                            .sortedBy { it.key.toIntOrNull() ?: 0 }
                                                            .forEach { (slotKey, slotInfo) ->
                                                                add(
                                                                    kotlinx.serialization.json.buildJsonObject {
                                                                        put(
                                                                            "user_id",
                                                                            kotlinx.serialization.json.JsonPrimitive(slotInfo.userId),
                                                                        )
                                                                        put(
                                                                            "username",
                                                                            kotlinx.serialization.json.JsonPrimitive(slotInfo.username),
                                                                        )
                                                                        put(
                                                                            "slot",
                                                                            kotlinx.serialization.json.JsonPrimitive(
                                                                                slotKey.toIntOrNull() ?: 0,
                                                                            ),
                                                                        )
                                                                    },
                                                                )
                                                            }
                                                    }.toString()
                                            putExtra(GameActivity.EXTRA_ROOM_PLAYERS_JSON, playersJson)
                                            putExtra(GameActivity.EXTRA_IS_HOST, room.hostId == repo.currentUserId())
                                            putExtra(GameActivity.EXTRA_VOICE_LOCAL_PORT, voiceLocalPort)
                                            putExtra(GameActivity.EXTRA_VOICE_PEER_HANDLES, voicePeerHandles.toIntArray())
                                            putStringArrayListExtra(GameActivity.EXTRA_VOICE_PEER_ADDRS, voicePeerAddrs)
                                        }
                                    gameActivityLauncher.launch(intent)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
