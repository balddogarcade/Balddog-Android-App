package dog.bald.arcade.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.IpfsRomManager
import dog.bald.arcade.data.RoomDto
import dog.bald.arcade.data.RoomMessageDto
import dog.bald.arcade.data.RoomSlotInfo
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.diagnostics.AnalyticsHelper
import dog.bald.arcade.diagnostics.CrashlyticsHelper
import dog.bald.arcade.net.LanNsdManager
import dog.bald.arcade.net.LobbyLatencyMonitor
import dog.bald.arcade.net.LobbyPeerTarget
import dog.bald.arcade.ui.components.ArcadeBadge
import dog.bald.arcade.ui.components.ArcadeBadgeStyle
import dog.bald.arcade.ui.components.DiceBearPixelArtAvatar
import dog.bald.arcade.ui.components.GameCoverImage
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.DangerRed
import dog.bald.arcade.ui.theme.DangerRedBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.ReadyGreenAccent
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RoomLobbyScreen(
    repo: ArcadeRepository,
    lanNsd: LanNsdManager,
    initialRoom: RoomDto,
    game: GameDto?,
    myUsername: String,
    myEndpoint: String,
    myUdpPort: Int,
    myVoiceEndpoint: String = "",
    myAvatarSeed: String? = null,
    romManager: IpfsRomManager? = null,
    allGames: List<GameDto> = emptyList(),
    hasActiveOverlay: Boolean = false,
    onOpenUserProfile: (String) -> Unit = {},
    onOpenGameDetail: (GameDto) -> Unit = {},
    onLeave: (String?) -> Unit,
    onStartMatch: suspend (room: RoomDto, mySlot: Int) -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val myUid = repo.currentUserId().orEmpty()

    var room by remember { mutableStateOf(initialRoom) }
    var messages by remember { mutableStateOf<List<RoomMessageDto>>(emptyList()) }
    var chatInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var matchLaunched by remember { mutableStateOf(false) }
    var startingMatch by remember { mutableStateOf(false) }
    var preparingMatch by remember { mutableStateOf(false) }
    var failedPlayingLaunch by remember(initialRoom.id) { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var leftRoom by remember { mutableStateOf(false) }
    var initialJoinDone by remember(initialRoom.id) {
        mutableStateOf(
            myUid.isBlank() || initialRoom.slotsJson.values.any { it.userId == myUid },
        )
    }
    var showHostExitConfirm by remember { mutableStateOf(false) }
    var copiedRoomCode by remember { mutableStateOf(false) }
    var banCandidateSlot by remember { mutableStateOf<RoomSlotInfo?>(null) }
    var romPrepProgress by remember(game?.id) { mutableStateOf<Float?>(null) }
    var romPrepStage by remember(game?.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(game?.id, romManager) {
        val g = game ?: return@LaunchedEffect
        val rm = romManager ?: return@LaunchedEffect
        runCatching {
            rm.ensureGameReady(
                game = g,
                allGames = allGames,
            ) { stage, progress ->
                romPrepStage = stage
                romPrepProgress = progress.coerceIn(0f, 1f)
            }
        }.onFailure { t ->
            CrashlyticsHelper.recordNonFatal(t)
            errorMsg = "Error descargando ROM: ${t.toUserMessage()}"
        }
        romPrepProgress = null
        romPrepStage = null
    }

    LaunchedEffect(room.bannedUserIds, myUid) {
        if (!leftRoom && myUid.isNotBlank() && room.bannedUserIds.any { it.equals(myUid, ignoreCase = true) }) {
            leftRoom = true
            onLeave("Fuiste baneado de esta sala por el anfitrión.")
        }
    }

    val isOnlineRoom = room.mode != "lan"
    val latencyMonitor =
        remember(room.id, myUdpPort, myUid, isOnlineRoom) {
            LobbyLatencyMonitor(
                localPort = myUdpPort,
                myUserId = myUid,
                measureInternetPing = isOnlineRoom,
            )
        }
    val latenciesMs by latencyMonitor.latenciesMs.collectAsState()

    val isHost = room.hostId == myUid
    val mySlot =
        room.slotsJson.entries
            .firstOrNull { it.value.userId == myUid }
            ?.key
            ?.toIntOrNull()

    val discoveredEndpoint by latencyMonitor.publicEndpoint.collectAsState()
    LaunchedEffect(discoveredEndpoint, mySlot) {
        val rawEp = discoveredEndpoint ?: return@LaunchedEffect
        val slot = mySlot ?: return@LaunchedEffect
        val localIp = lanNsd.getLocalIpv4Address()
        val ep =
            dog.bald.arcade.net.NetplayEndpointResolver
                .formatCandidateEndpoint(rawEp, localIp, myUdpPort)
        val voicePort = myVoiceEndpoint.substringBefore('|').substringAfterLast(':').toIntOrNull() ?: (myUdpPort + 1000)
        val voiceEp =
            if (myVoiceEndpoint.isNotBlank()) {
                val rawVoice = myVoiceEndpoint.substringBefore('|').trim()
                dog.bald.arcade.net.NetplayEndpointResolver
                    .formatCandidateEndpoint(rawVoice, localIp, voicePort)
            } else {
                null
            }
        val currentSlotEp = room.slotsJson[slot.toString()]?.endpoint
        if (room.mode != "lan" && ep.isNotBlank() && currentSlotEp != ep) {
            val isCurrentPrivate = currentSlotEp == null || isPrivateIp(currentSlotEp)
            if (isCurrentPrivate || currentSlotEp != ep) {
                runCatching {
                    val updated =
                        repo.updateRoomSlot(
                            roomId = room.id,
                            action = "claim",
                            slotIndex = slot,
                            endpoint = ep,
                            voiceEndpoint = voiceEp,
                        )
                    room = updated
                }
            }
        }
    }

    DisposableEffect(latencyMonitor) {
        latencyMonitor.start(scope)
        onDispose {
            latencyMonitor.close()
        }
    }

    LaunchedEffect(room.slotsJson) {
        latencyMonitor.updatePeers(
            room.slotsJson.values.map { LobbyPeerTarget(userId = it.userId, endpoint = it.endpoint) },
        )
    }

    // Si somos Host de una sala LAN pública, anunciamos por NsdManager
    DisposableEffect(room.id, isHost, room.isPrivate) {
        if (isHost && room.mode == "lan" && !room.isPrivate && room.localPort != null) {
            lanNsd.registerRoom(room.code, game?.title ?: "Arcade", room.localPort ?: 7000)
        }
        onDispose {
            if (isHost && room.mode == "lan" && !room.isPrivate) {
                lanNsd.unregisterRoom()
            }
        }
    }

    // Auto-asignar el primer slot libre al entrar si aún no tiene slot
    LaunchedEffect(room.id, myUid) {
        if (myUid.isNotBlank() && room.slotsJson.values.none { it.userId == myUid }) {
            runCatching {
                repo.updateRoomSlot(
                    roomId = room.id,
                    action = "join",
                    endpoint = myEndpoint,
                    voiceEndpoint = myVoiceEndpoint.ifBlank { null },
                )
            }.onSuccess { joined ->
                room = joined
                initialJoinDone = true
            }.onFailure { err ->
                if (err is CancellationException) throw err
                leftRoom = true
                latencyMonitor.close()
                onLeave(err.toUserMessage("No se pudo unir a la sala"))
            }
        } else {
            initialJoinDone = true
        }
    }

    fun triggerMatchLaunch(
        targetRoom: RoomDto,
        slot: Int,
    ) {
        if (leftRoom || matchLaunched || preparingMatch) return
        if (targetRoom.mode != "lan") {
            val hasPrivateEp = targetRoom.slotsJson.values.any { isPrivateIp(it.endpoint) }
            if (hasPrivateEp) {
                errorMsg = "Esperando resolución de dirección pública STUN para jugar por internet..."
                return
            }
        }
        preparingMatch = true
        matchLaunched = true
        errorMsg = null
        failedPlayingLaunch = false
        latencyMonitor.close()
        launchJob =
            scope.launch {
                val result = onStartMatch(targetRoom, slot)
                if (result.isSuccess) {
                    preparingMatch = false
                } else {
                    val err = result.exceptionOrNull()
                    if (err !is CancellationException) {
                        matchLaunched = false
                        preparingMatch = false
                        startingMatch = false
                        if (targetRoom.status == "playing") {
                            failedPlayingLaunch = true
                        }
                        errorMsg = err?.toUserMessage("Error preparando partida")
                            ?: "Error preparando partida"
                        latencyMonitor.start(scope)
                    }
                }
            }
    }

    fun leaveCleanly(reason: String? = null) {
        if (leftRoom) return
        if (matchLaunched && !preparingMatch) return
        leftRoom = true
        launchJob?.cancel()
        launchJob = null
        preparingMatch = false
        matchLaunched = false
        startingMatch = false
        latencyMonitor.close()
        scope.launch {
            withContext(NonCancellable) {
                if (isHost) {
                    runCatching { repo.deleteRoom(room.id) }
                } else if (reason == null && myUid.isNotBlank()) {
                    runCatching { repo.updateRoomSlot(roomId = room.id, action = "leave") }
                }
            }
            onLeave(reason)
        }
    }

    val requestExit: () -> Unit = {
        val hasOtherPlayers = room.slotsJson.values.any { it.userId != myUid }
        if (isHost && hasOtherPlayers) {
            showHostExitConfirm = true
        } else {
            leaveCleanly()
        }
    }

    BackHandler(enabled = !leftRoom && (!matchLaunched || preparingMatch) && !hasActiveOverlay) {
        requestExit()
    }

    // Si el anfitrión sale de la pantalla sin usar el botón (o se destruye el composable), eliminar la sala
    DisposableEffect(room.id, isHost) {
        onDispose {
            val changingConfig = activity?.isChangingConfigurations == true
            if (isHost && !matchLaunched && !leftRoom && !changingConfig) {
                leftRoom = true
                val roomIdToDelete = room.id
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { repo.deleteRoom(roomIdToDelete) }
                }
            }
        }
    }

    // Heartbeat del anfitrión para detectar cierre forzado o pérdida de conexión
    LaunchedEffect(room.id, isHost) {
        if (!isHost) return@LaunchedEffect
        while (!leftRoom) {
            if (!matchLaunched || preparingMatch) {
                runCatching { repo.touchRoomHeartbeat(room.id) }
            }
            delay(4000)
        }
    }

    // Polling ligero de sala y chat (con guardia de disparo único para iniciar partida)
    LaunchedEffect(room.id) {
        while (!initialJoinDone && !leftRoom) {
            delay(100)
        }
        if (leftRoom) return@LaunchedEffect
        var lastUpdatedAt = room.updatedAt
        var lastHeartbeatChangeMs = SystemClock.elapsedRealtime()
        while (!leftRoom) {
            if (matchLaunched || preparingMatch) {
                lastHeartbeatChangeMs = SystemClock.elapsedRealtime()
                delay(500)
                continue
            }
            runCatching {
                val updated = repo.fetchRoomById(room.id)
                if (updated == null || updated.status == "closed") {
                    if (!leftRoom && (!matchLaunched || preparingMatch)) {
                        leftRoom = true
                        launchJob?.cancel()
                        launchJob = null
                        preparingMatch = false
                        matchLaunched = false
                        latencyMonitor.close()
                        onLeave("El anfitrión se fue de la sala.")
                    }
                    return@LaunchedEffect
                }
                if (!isHost && updated.status == "waiting") {
                    if (updated.updatedAt != lastUpdatedAt) {
                        lastUpdatedAt = updated.updatedAt
                        lastHeartbeatChangeMs = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - lastHeartbeatChangeMs > 20_500L) {
                        if (!leftRoom && (!matchLaunched || preparingMatch)) {
                            leftRoom = true
                            runCatching { repo.updateRoomSlot(room.id, "close_stale") }
                            latencyMonitor.close()
                            onLeave("El anfitrión se fue de la sala.")
                        }
                        return@LaunchedEffect
                    }
                }
                if (updated.status != "playing") {
                    failedPlayingLaunch = false
                }
                room = updated
                val updatedSlot =
                    updated.slotsJson.entries
                        .firstOrNull { it.value.userId == myUid }
                        ?.key
                        ?.toIntOrNull()
                if (updated.status == "playing" && updatedSlot != null && !matchLaunched && !preparingMatch && !failedPlayingLaunch) {
                    triggerMatchLaunch(updated, updatedSlot)
                }
                messages = repo.fetchRoomMessages(room.id)
            }
            delay(1500)
        }
    }

    fun claimSlot(slotIdx: Int) {
        if (leftRoom || matchLaunched || preparingMatch || startingMatch) return
        scope.launch {
            try {
                errorMsg = null
                room =
                    repo.updateRoomSlot(
                        roomId = room.id,
                        action = "claim",
                        slotIndex = slotIdx,
                        endpoint = myEndpoint,
                        voiceEndpoint = myVoiceEndpoint.ifBlank { null },
                    )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                errorMsg = t.toUserMessage("Error actualizando sala")
            }
        }
    }

    fun toggleReady() {
        if (leftRoom || matchLaunched || preparingMatch || startingMatch) return
        val slot = mySlot ?: return
        val myCurrentReady = room.slotsJson[slot.toString()]?.ready == true
        scope.launch {
            try {
                errorMsg = null
                room =
                    repo.updateRoomSlot(
                        roomId = room.id,
                        action = "ready",
                        ready = !myCurrentReady,
                    )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                errorMsg = t.toUserMessage("Error cambiando estado")
            }
        }
    }

    LaunchedEffect(copiedRoomCode) {
        if (copiedRoomCode) {
            delay(2000)
            copiedRoomCode = false
        }
    }

    if (showHostExitConfirm) {
        AlertDialog(
            onDismissRequest = { showHostExitConfirm = false },
            containerColor = CarbonSurface,
            shape = RectangleShape,
            title = {
                Text(
                    text = "CERRAR SALA",
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Text(
                    text = "Eres el anfitrión. Si sales ahora, la sala se cerrará y los demás jugadores serán desconectados.",
                    color = SilverGray,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHostExitConfirm = false
                        leaveCleanly()
                    },
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = PureWhite,
                        ),
                ) {
                    Text("CERRAR SALA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHostExitConfirm = false },
                    shape = RectangleShape,
                ) {
                    Text("CANCELAR", color = SilverGray, fontSize = 12.sp)
                }
            },
        )
    }

    val quickActions =
        remember {
            listOf(
                "¡Listo!" to Icons.Default.Check,
                "¡GG!" to Icons.Default.EmojiEvents,
                "¡Espera!" to Icons.Default.HourglassEmpty,
            )
        }
    val chatListState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            chatListState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun sendChatMessage() {
        val txt = chatInput.trim()
        if (txt.isBlank()) return
        chatInput = ""
        scope.launch {
            runCatching { repo.sendRoomMessage(room.id, txt) }
                .onSuccess { sent ->
                    if (messages.none { it.id == sent.id }) {
                        messages = messages + sent
                    }
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    errorMsg = err.toUserMessage("No se pudo enviar el mensaje")
                }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ObsidianBlack)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header Card: Poster 3:4 grande + Metadatos de Sala + Compartir ID + Botón Salir Rojo
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(CarbonSurface)
                    .border(1.dp, CharcoalBorder)
                    .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            GameCoverImage(
                slug = game?.slug,
                contentDescription = game?.title,
                contentScale = ContentScale.Fit,
                shape = RectangleShape,
                backgroundColor = ObsidianBlack,
                modifier =
                    Modifier
                        .width(72.dp)
                        .height(96.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier =
                                Modifier
                                    .background(GraphiteSurface)
                                    .border(1.dp, SteelBorder)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                        ) {
                            Icon(
                                imageVector = if (isOnlineRoom) Icons.Default.Public else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = PureWhite,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = room.mode.uppercase(),
                                color = PureWhite,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                            )
                        }
                        if (room.isPrivate) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier =
                                    Modifier
                                        .background(GraphiteSurface)
                                        .border(1.dp, CharcoalBorder)
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Sala privada",
                                    tint = SilverGray,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = "PRIVADA",
                                    color = SilverGray,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }

                    Button(
                        onClick = requestExit,
                        shape = RectangleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = DangerRed,
                                contentColor = PureWhite,
                            ),
                        border = BorderStroke(1.dp, DangerRedBorder),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Salir de la sala",
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "SALIR",
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }

                Text(
                    text = game?.title ?: "Arcade Match",
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (game != null) Modifier.clickable { onOpenGameDetail(game) } else Modifier,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArcadeBadge(
                        text = "#${room.code}",
                        style = ArcadeBadgeStyle.Solid,
                    )
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Código de sala", room.code))
                            copiedRoomCode = true
                        },
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, if (copiedRoomCode) PureWhite else CharcoalBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            imageVector = if (copiedRoomCode) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copiar ID de sala",
                            tint = if (copiedRoomCode) PureWhite else SilverGray,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (copiedRoomCode) "COPIADO" else "COPIAR",
                            color = if (copiedRoomCode) PureWhite else SilverGray,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val gameTitle = game?.title ?: "Arcade Match"
                            val magicLink = "https://bald.dog/room/${room.id}"
                            AnalyticsHelper.logShare("room", room.id, "android_sharesheet")
                            val shareBody = "¡Únete a mi sala en bald.dog ARCADE!\nJuego: $gameTitle\nCódigo: #${room.code} (${room.mode.uppercase()})\nLink directo: $magicLink"
                            val sendIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Sala #${room.code} · $gameTitle")
                                    putExtra(Intent.EXTRA_TEXT, shareBody)
                                }
                            context.startActivity(Intent.createChooser(sendIntent, "Compartir link de sala"))
                        },
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, SteelBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartir ID de sala",
                            tint = PureWhite,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "COMPARTIR",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        if (preparingMatch) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(GraphiteSurface)
                        .border(1.dp, SteelBorder)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Preparando ROM y archivos de la partida...",
                    color = PureWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (errorMsg != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(CarbonSurface)
                        .border(1.dp, DangerRedBorder)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = errorMsg ?: "",
                    color = PureWhite,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                if (room.status == "playing" && mySlot != null && !preparingMatch && !leftRoom) {
                    OutlinedButton(
                        onClick = {
                            failedPlayingLaunch = false
                            triggerMatchLaunch(room, mySlot)
                        },
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, PureWhite),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(
                            text = "REINTENTAR",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        // Slots 1P..4P en una sola fila con avatares
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                tint = SilverGray,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "JUGADORES (${room.slotsJson.size}/${room.maxPlayers}) · TOCA UN SLOT LIBRE",
                color = MutedGray,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (slot in 0 until room.maxPlayers) {
                val occupant = room.slotsJson[slot.toString()]
                val isMine = occupant?.userId == myUid
                val isSlotHost = occupant != null && occupant.userId == room.hostId
                val pingMs = occupant?.let { latenciesMs[it.userId] }
                PlayerSlotCard(
                    slotIndex = slot,
                    occupant = occupant,
                    isMine = isMine,
                    isSlotHost = isSlotHost,
                    isOnlineRoom = isOnlineRoom,
                    pingMs = pingMs,
                    myAvatarSeed = myAvatarSeed,
                    canBan = isHost && occupant != null && !isMine && room.status == "waiting",
                    onClaim = { claimSlot(slot) },
                    onInspectOccupant = occupant?.let { occ -> { onOpenUserProfile(occ.userId) } },
                    onBanOccupant = occupant?.let { occ -> { banCandidateSlot = occ } },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Chat rápido en español con iconografía
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickActions) { (label, icon) ->
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { repo.sendRoomMessage(room.id, label) }
                                .onSuccess { sent ->
                                    if (messages.none { it.id == sent.id }) {
                                        messages = messages + sent
                                    }
                                }.onFailure { err ->
                                    if (err is CancellationException) throw err
                                    errorMsg = err.toUserMessage("No se pudo enviar el mensaje")
                                }
                        }
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = GraphiteSurface),
                    border = BorderStroke(1.dp, CharcoalBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = SilverGray,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(label, color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(CarbonSurface, RectangleShape)
                    .border(1.dp, CharcoalBorder)
                    .padding(10.dp),
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = null,
                        tint = MutedGray,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "Sin mensajes aún. Saluda o usa un mensaje rápido.",
                        color = MutedGray,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = chatListState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Text(
                            text =
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = PureWhite, fontWeight = FontWeight.SemiBold)) {
                                        append("${msg.username}: ")
                                    }
                                    withStyle(SpanStyle(color = SilverGray)) {
                                        append(msg.message)
                                    }
                                },
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onOpenUserProfile(msg.userId) },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = { Text("Mensaje rápido...", color = MutedGray, fontSize = 12.sp) },
                singleLine = true,
                shape = RectangleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendChatMessage() }),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CarbonSurface,
                        unfocusedContainerColor = CarbonSurface,
                        focusedBorderColor = PureWhite,
                        unfocusedBorderColor = CharcoalBorder,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        cursorColor = PureWhite,
                    ),
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = chatInput.isNotBlank(),
                onClick = { sendChatMessage() },
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = PureWhite,
                        contentColor = PureBlack,
                        disabledContainerColor = GraphiteSurface,
                        disabledContentColor = MutedGray,
                    ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(50.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar mensaje",
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("ENVIAR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (!isHost && mySlot != null) {
                if (room.status == "playing") {
                    Button(
                        enabled = !preparingMatch && !matchLaunched && !leftRoom,
                        onClick = {
                            failedPlayingLaunch = false
                            triggerMatchLaunch(room, mySlot)
                        },
                        shape = RectangleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = PureWhite,
                                contentColor = PureBlack,
                                disabledContainerColor = GraphiteSurface,
                                disabledContentColor = MutedGray,
                            ),
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(48.dp),
                    ) {
                        Icon(
                            imageVector = if (preparingMatch) Icons.Default.HourglassTop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (preparingMatch) "PREPARANDO ROM..." else "REINTENTAR PARTIDA",
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    val amReady = room.slotsJson[mySlot.toString()]?.ready == true
                    val isDownloadingRom = romPrepProgress != null
                    Button(
                        enabled = !isDownloadingRom && !preparingMatch && !matchLaunched && !leftRoom,
                        onClick = { toggleReady() },
                        shape = RectangleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = if (amReady) GraphiteSurface else PureWhite,
                                contentColor = if (amReady) PureWhite else PureBlack,
                                disabledContainerColor = GraphiteSurface,
                                disabledContentColor = PureWhite,
                            ),
                        border = BorderStroke(1.dp, if (amReady) ReadyGreenAccent else PureWhite),
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(48.dp),
                    ) {
                        if (isDownloadingRom) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = (romPrepStage ?: "DESCARGANDO ROM...").uppercase(),
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                LinearProgressIndicator(
                                    progress = { romPrepProgress ?: 0f },
                                    color = ReadyGreenAccent,
                                    trackColor = CharcoalBorder,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(4.dp),
                                )
                            }
                        } else {
                            Icon(
                                imageVector = if (amReady) Icons.Default.CheckCircle else Icons.Default.Check,
                                contentDescription = null,
                                tint = if (amReady) ReadyGreenAccent else PureBlack,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (amReady) "LISTO · TOCA PARA ESPERAR" else "MARCAR LISTO",
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
            if (isHost && mySlot != null) {
                val seated = room.slotsJson.values
                val canStart = seated.size >= 2 && seated.all { it.ready }
                val waitingLabel =
                    if (seated.size < 2) {
                        "ESPERANDO JUGADORES (${seated.size}/2+)"
                    } else {
                        "ESPERANDO QUE TODOS ESTÉN LISTOS"
                    }
                val buttonLabel =
                    when {
                        preparingMatch -> "PREPARANDO ROM..."
                        startingMatch -> "INICIANDO..."
                        room.status == "playing" && failedPlayingLaunch -> "REINTENTAR PARTIDA"
                        canStart -> "INICIAR PARTIDA"
                        else -> waitingLabel
                    }
                val buttonEnabled =
                    (canStart || (room.status == "playing" && failedPlayingLaunch)) &&
                        !startingMatch &&
                        !preparingMatch &&
                        !matchLaunched
                Button(
                    enabled = buttonEnabled,
                    onClick = {
                        if (startingMatch || preparingMatch || matchLaunched || leftRoom) return@Button
                        if (room.status == "playing") {
                            failedPlayingLaunch = false
                            triggerMatchLaunch(room, mySlot)
                            return@Button
                        }
                        startingMatch = true
                        errorMsg = null
                        scope.launch {
                            runCatching {
                                repo.updateRoomSlot(roomId = room.id, action = "start")
                            }.onSuccess { startedRoom ->
                                room = startedRoom
                                startingMatch = false
                                val resolvedSlot =
                                    startedRoom.slotsJson.entries
                                        .firstOrNull { it.value.userId == myUid }
                                        ?.key
                                        ?.toIntOrNull()
                                        ?: mySlot
                                triggerMatchLaunch(startedRoom, resolvedSlot)
                            }.onFailure { err ->
                                if (err is CancellationException) throw err
                                startingMatch = false
                                errorMsg = err.toUserMessage("No se pudo iniciar la partida")
                            }
                        }
                    },
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = PureWhite,
                            contentColor = PureBlack,
                            disabledContainerColor = GraphiteSurface,
                            disabledContentColor = MutedGray,
                        ),
                    border = if (buttonEnabled) null else BorderStroke(1.dp, CharcoalBorder),
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp),
                ) {
                    Icon(
                        imageVector =
                            if (buttonEnabled && !preparingMatch && !startingMatch) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.HourglassTop
                            },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buttonLabel,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    banCandidateSlot?.let { target ->
        AlertDialog(
            onDismissRequest = { banCandidateSlot = null },
            containerColor = GraphiteSurface,
            title = {
                Text(
                    text = "Banear a ${target.username}",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Text(
                    text = "${target.username} será expulsado, sus mensajes en esta sala se eliminarán y no podrá volver a entrar a menos que se cree una nueva sala.",
                    color = SilverGray,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        banCandidateSlot = null
                        scope.launch {
                            runCatching {
                                repo.banRoomPlayer(room.id, target.userId)
                            }.onSuccess { updated ->
                                room = updated
                                AnalyticsHelper.logRoomPlayerBanned(game?.slug.orEmpty())
                            }.onFailure { t ->
                                CrashlyticsHelper.recordNonFatal(t)
                                errorMsg = t.toUserMessage()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = PureWhite),
                    shape = RectangleShape,
                ) {
                    Text("BANEAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { banCandidateSlot = null }) {
                    Text("CANCELAR", color = PureWhite)
                }
            },
        )
    }
}

@Composable
private fun PlayerSlotCard(
    slotIndex: Int,
    occupant: RoomSlotInfo?,
    isMine: Boolean,
    isSlotHost: Boolean,
    isOnlineRoom: Boolean,
    pingMs: Int?,
    myAvatarSeed: String? = null,
    canBan: Boolean = false,
    onClaim: () -> Unit,
    onInspectOccupant: (() -> Unit)? = null,
    onBanOccupant: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        when {
            occupant == null -> CharcoalBorder
            occupant.ready -> PureWhite
            else -> SteelBorder
        }
    Card(
        shape = RectangleShape,
        colors =
            CardDefaults.cardColors(
                containerColor = if (isMine) GraphiteSurface else CarbonSurface,
            ),
        border = BorderStroke(1.dp, borderColor),
        modifier =
            modifier
                .height(132.dp)
                .clickable {
                    if (occupant == null) onClaim() else onInspectOccupant?.invoke()
                },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${slotIndex + 1}P",
                    color = if (occupant != null) PureWhite else MutedGray,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                if (occupant != null) {
                    val pingText =
                        when {
                            isMine && !isOnlineRoom -> "LOCAL"
                            pingMs != null -> "$pingMs ms"
                            else -> "-- ms"
                        }
                    val pingColor =
                        when {
                            pingMs == null -> MutedGray
                            pingMs <= 80 -> PureWhite
                            pingMs <= 150 -> SilverGray
                            else -> MutedGray
                        }
                    Text(
                        text = pingText,
                        color = pingColor,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (isSlotHost) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Anfitrión",
                        tint = PureWhite,
                        modifier =
                            Modifier
                                .size(12.dp)
                                .align(Alignment.CenterEnd),
                    )
                } else if (isMine) {
                    Text(
                        text = "TÚ",
                        color = SilverGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 9.sp,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                } else if (canBan && onBanOccupant != null) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = "Banear jugador",
                        tint = DangerRed,
                        modifier =
                            Modifier
                                .size(14.dp)
                                .align(Alignment.CenterEnd)
                                .clickable { onBanOccupant() },
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .background(ObsidianBlack)
                        .border(1.dp, if (occupant != null) SteelBorder else CharcoalBorder),
            ) {
                if (occupant != null) {
                    val effectiveAvatarSeed =
                        when {
                            isMine && !myAvatarSeed.isNullOrBlank() -> myAvatarSeed
                            !occupant.avatarSeed.isNullOrBlank() -> occupant.avatarSeed
                            else -> occupant.username.ifBlank { occupant.userId }
                        }
                    DiceBearPixelArtAvatar(
                        seed = effectiveAvatarSeed,
                        modifier = Modifier.size(38.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Slot libre",
                        tint = MutedGray,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Text(
                text = occupant?.username ?: "LIBRE",
                color = if (occupant != null) PureWhite else MutedGray,
                fontSize = 11.sp,
                fontWeight = if (occupant != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (occupant != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = if (occupant.ready) Icons.Default.CheckCircle else Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (occupant.ready) ReadyGreenAccent else MutedGray,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = if (occupant.ready) "LISTO" else "ESPERA",
                        color = if (occupant.ready) PureWhite else MutedGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Text(
                    text = "TOCAR",
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun isPrivateIp(endpoint: String): Boolean =
    dog.bald.arcade.net.NetplayEndpointResolver
        .isPrivateEndpoint(endpoint)
