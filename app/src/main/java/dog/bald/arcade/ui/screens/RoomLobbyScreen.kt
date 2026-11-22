package dog.bald.arcade.ui.screens

import dog.bald.arcade.data.toUserMessage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.RoomDto
import dog.bald.arcade.data.RoomMessageDto
import dog.bald.arcade.data.RoomSlotInfo
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
import dog.bald.arcade.ui.theme.ReadyGreenAccent
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    onLeave: (String?) -> Unit,
    onStartMatch: (room: RoomDto, mySlot: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val myUid = repo.currentUserId().orEmpty()

    var room by remember { mutableStateOf(initialRoom) }
    var messages by remember { mutableStateOf<List<RoomMessageDto>>(emptyList()) }
    var chatInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var matchLaunched by remember { mutableStateOf(false) }
    var leftRoom by remember { mutableStateOf(false) }
    var showHostExitConfirm by remember { mutableStateOf(false) }
    var copiedRoomCode by remember { mutableStateOf(false) }

    val isOnlineRoom = room.mode != "lan"
    val latencyMonitor = remember(room.id, myUdpPort, myUid, isOnlineRoom) {
        LobbyLatencyMonitor(
            localPort = myUdpPort,
            myUserId = myUid,
            measureInternetPing = isOnlineRoom
        )
    }
    val latenciesMs by latencyMonitor.latenciesMs.collectAsState()

    val isHost = room.hostId == myUid
    val mySlot = room.slotsJson.entries.firstOrNull { it.value.userId == myUid }?.key?.toIntOrNull()

    DisposableEffect(latencyMonitor) {
        latencyMonitor.start(scope)
        onDispose {
            latencyMonitor.close()
        }
    }

    LaunchedEffect(room.slotsJson) {
        latencyMonitor.updatePeers(
            room.slotsJson.values.map { LobbyPeerTarget(userId = it.userId, endpoint = it.endpoint) }
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
        if (myUid.isNotBlank() && mySlot == null) {
            val latest = runCatching { repo.fetchRoomById(room.id) }.getOrNull() ?: room
            room = latest
            val alreadySeated = latest.slotsJson.values.any { it.userId == myUid }
            if (!alreadySeated) {
                val freeSlot = (0 until latest.maxPlayers).firstOrNull { !latest.slotsJson.containsKey(it.toString()) }
                if (freeSlot != null) {
                    val next = latest.slotsJson.toMutableMap()
                    next[freeSlot.toString()] = RoomSlotInfo(
                        userId = myUid,
                        username = myUsername,
                        endpoint = myEndpoint,
                        voiceEndpoint = myVoiceEndpoint.ifBlank { null },
                        ready = isHost
                    )
                    runCatching {
                        repo.updateRoomSlots(latest.id, next)
                        room = latest.copy(slotsJson = next)
                    }
                }
            }
        }
    }

    fun leaveCleanly(reason: String? = null) {
        if (leftRoom || matchLaunched) return
        leftRoom = true
        latencyMonitor.close()
        scope.launch {
            withContext(NonCancellable) {
                if (isHost) {
                    runCatching { repo.deleteRoom(room.id) }
                } else if (reason == null && myUid.isNotBlank()) {
                    runCatching {
                        val latest = repo.fetchRoomById(room.id) ?: room
                        val next = latest.slotsJson.filterValues { it.userId != myUid }
                        if (next.size != latest.slotsJson.size) {
                            repo.updateRoomSlots(room.id, next)
                        }
                    }
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

    BackHandler(enabled = !leftRoom && !matchLaunched) {
        requestExit()
    }

    // Si el anfitrión sale de la pantalla sin usar el botón (o se destruye el composable), eliminar la sala
    DisposableEffect(room.id, isHost) {
        onDispose {
            if (isHost && !matchLaunched && !leftRoom) {
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
        while (!matchLaunched && !leftRoom) {
            runCatching { repo.touchRoomHeartbeat(room.id) }
            delay(4000)
        }
    }

    // Polling ligero de sala y chat (con guardia de disparo único para iniciar partida)
    LaunchedEffect(room.id) {
        var lastUpdatedAt = initialRoom.updatedAt
        var lastHeartbeatChangeMs = SystemClock.elapsedRealtime()
        while (!matchLaunched && !leftRoom) {
            runCatching {
                val updated = repo.fetchRoomById(room.id)
                if (updated == null || updated.status == "closed") {
                    if (!leftRoom && !matchLaunched) {
                        leftRoom = true
                        latencyMonitor.close()
                        onLeave("El anfitrión se fue de la sala.")
                    }
                    return@LaunchedEffect
                }
                if (!isHost && updated.status == "waiting") {
                    if (updated.updatedAt != lastUpdatedAt) {
                        lastUpdatedAt = updated.updatedAt
                        lastHeartbeatChangeMs = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - lastHeartbeatChangeMs > 15_000L) {
                        if (!leftRoom && !matchLaunched) {
                            leftRoom = true
                            runCatching { repo.closeRoom(room.id) }
                            latencyMonitor.close()
                            onLeave("El anfitrión se fue de la sala.")
                        }
                        return@LaunchedEffect
                    }
                }
                room = updated
                val updatedSlot = updated.slotsJson.entries
                    .firstOrNull { it.value.userId == myUid }
                    ?.key
                    ?.toIntOrNull()
                if (updated.status == "playing" && updatedSlot != null && !matchLaunched) {
                    matchLaunched = true
                    latencyMonitor.close()
                    onStartMatch(updated, updatedSlot)
                }
                messages = repo.fetchRoomMessages(room.id)
            }
            delay(1500)
        }
    }

    fun claimSlot(slotIdx: Int) {
        scope.launch {
            try {
                val next = room.slotsJson.toMutableMap()
                if (next.containsKey(slotIdx.toString())) return@launch
                // Quitar slot anterior del usuario si tenía uno
                val oldKey = next.entries.firstOrNull { it.value.userId == myUid }?.key
                if (oldKey != null) next.remove(oldKey)
                next[slotIdx.toString()] = RoomSlotInfo(
                    userId = myUid,
                    username = myUsername,
                    endpoint = myEndpoint,
                    voiceEndpoint = myVoiceEndpoint.ifBlank { null },
                    ready = isHost
                )
                repo.updateRoomSlots(room.id, next)
                room = room.copy(slotsJson = next)
            } catch (t: Throwable) {
                errorMsg = t.toUserMessage("Error actualizando sala")
            }
        }
    }

    fun toggleReady() {
        val slot = mySlot ?: return
        scope.launch {
            try {
                val next = room.slotsJson.toMutableMap()
                val cur = next[slot.toString()] ?: return@launch
                next[slot.toString()] = cur.copy(ready = !cur.ready)
                repo.updateRoomSlots(room.id, next)
                room = room.copy(slotsJson = next)
            } catch (t: Throwable) {
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
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "Eres el anfitrión. Si sales ahora, la sala se cerrará y los demás jugadores serán desconectados.",
                    color = SilverGray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHostExitConfirm = false
                        leaveCleanly()
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerRed,
                        contentColor = PureWhite
                    )
                ) {
                    Text("CERRAR SALA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHostExitConfirm = false },
                    shape = RectangleShape
                ) {
                    Text("CANCELAR", color = SilverGray, fontSize = 12.sp)
                }
            }
        )
    }

    val quickStickers = listOf("Ready!", "Insert Coin!", "GG!", "Wait...")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card: Poster 3:4 grande + Metadatos de Sala + Compartir ID + Botón Salir Rojo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CarbonSurface)
                .border(1.dp, CharcoalBorder)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            GameCoverImage(
                slug = game?.slug,
                contentDescription = game?.title,
                contentScale = ContentScale.Fit,
                shape = RectangleShape,
                backgroundColor = ObsidianBlack,
                modifier = Modifier
                    .width(72.dp)
                    .height(96.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .background(GraphiteSurface)
                                .border(1.dp, SteelBorder)
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = if (isOnlineRoom) Icons.Default.Public else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = PureWhite,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = room.mode.uppercase(),
                                color = PureWhite,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp
                            )
                        }
                        if (room.isPrivate) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier
                                    .background(GraphiteSurface)
                                    .border(1.dp, CharcoalBorder)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Sala privada",
                                    tint = SilverGray,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "PRIVADA",
                                    color = SilverGray,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Button(
                        onClick = requestExit,
                        shape = RectangleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = PureWhite
                        ),
                        border = BorderStroke(1.dp, DangerRedBorder),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Salir de la sala",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "SALIR",
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
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
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArcadeBadge(
                        text = "#${room.code}",
                        style = ArcadeBadgeStyle.Solid
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
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = if (copiedRoomCode) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copiar ID de sala",
                            tint = if (copiedRoomCode) PureWhite else SilverGray,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (copiedRoomCode) "COPIADO" else "COPIAR",
                            color = if (copiedRoomCode) PureWhite else SilverGray,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val gameTitle = game?.title ?: "Arcade Match"
                            val shareBody = "¡Únete a mi sala en bald.dog ARCADE!\nJuego: $gameTitle\nCódigo de sala: #${room.code} (${room.mode.uppercase()})"
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Sala #${room.code} · $gameTitle")
                                putExtra(Intent.EXTRA_TEXT, shareBody)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Compartir ID de sala"))
                        },
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, SteelBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartir ID de sala",
                            tint = PureWhite,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "COMPARTIR",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        if (errorMsg != null) {
            Text(errorMsg ?: "", color = SilverGray, fontSize = 12.sp)
        }

        // Slots 1P..4P en una sola fila con avatares
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                tint = SilverGray,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "JUGADORES (${room.slotsJson.size}/${room.maxPlayers}) · TOCA UN SLOT LIBRE",
                color = MutedGray,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
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
                    onClaim = { claimSlot(slot) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Chat rápido / Stickers
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickStickers) { sticker ->
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { repo.sendRoomMessage(room.id, myUsername, sticker) }
                        }
                    },
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Text(sticker, color = PureWhite, fontSize = 11.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(CarbonSurface, RectangleShape)
                .padding(10.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(messages, key = { it.id }) { msg ->
                    Text(
                        text = "${msg.username}: ${msg.message}",
                        color = SilverGray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = { Text("Mensaje rápido...", fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PureWhite,
                    unfocusedBorderColor = CharcoalBorder,
                    focusedTextColor = PureWhite,
                    unfocusedTextColor = PureWhite
                ),
                modifier = Modifier.weight(1f)
            )
            Button(
                enabled = chatInput.isNotBlank(),
                onClick = {
                    val txt = chatInput.trim()
                    chatInput = ""
                    scope.launch {
                        runCatching { repo.sendRoomMessage(room.id, myUsername, txt) }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
            ) {
                Text("ENVIAR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (!isHost && mySlot != null) {
                Button(
                    onClick = { toggleReady() },
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteSurface, contentColor = PureWhite),
                    border = BorderStroke(1.dp, PureWhite),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("LISTO / READY", fontWeight = FontWeight.Bold)
                }
            }
            if (isHost && mySlot != null) {
                val seated = room.slotsJson.values
                val canStart = seated.size >= 2 && seated.all { it.ready }
                Button(
                    enabled = canStart,
                    onClick = {
                        if (matchLaunched) return@Button
                        scope.launch {
                            runCatching {
                                repo.updateRoomSlots(room.id, room.slotsJson, status = "playing")
                                matchLaunched = true
                                latencyMonitor.close()
                                onStartMatch(room.copy(status = "playing"), mySlot)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (canStart) "INICIAR PARTIDA" else "ESPERANDO JUGADORES (${seated.size}/2+)",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
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
    onClaim: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        occupant == null -> CharcoalBorder
        occupant.ready -> PureWhite
        else -> SteelBorder
    }
    Card(
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isMine) GraphiteSurface else CarbonSurface
        ),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(132.dp)
            .clickable(enabled = occupant == null, onClick = onClaim)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${slotIndex + 1}P",
                    color = if (occupant != null) PureWhite else MutedGray,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                if (isSlotHost) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Anfitrión",
                        tint = PureWhite,
                        modifier = Modifier.size(12.dp)
                    )
                } else if (isMine) {
                    Text(
                        text = "TÚ",
                        color = SilverGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 9.sp
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .background(ObsidianBlack)
                    .border(1.dp, if (occupant != null) SteelBorder else CharcoalBorder)
            ) {
                if (occupant != null) {
                    DiceBearPixelArtAvatar(
                        seed = occupant.username.ifBlank { occupant.userId },
                        modifier = Modifier.size(38.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Slot libre",
                        tint = MutedGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = occupant?.username ?: "LIBRE",
                color = if (occupant != null) PureWhite else MutedGray,
                fontSize = 11.sp,
                fontWeight = if (occupant != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (occupant != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = if (occupant.ready) Icons.Default.CheckCircle else Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (occupant.ready) ReadyGreenAccent else MutedGray,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = if (occupant.ready) "LISTO" else "ESPERA",
                            color = if (occupant.ready) PureWhite else MutedGray,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    val pingText = when {
                        isMine && !isOnlineRoom -> "LOCAL"
                        pingMs != null -> "$pingMs ms"
                        else -> "-- ms"
                    }
                    val pingColor = when {
                        pingMs == null -> MutedGray
                        pingMs <= 80 -> PureWhite
                        pingMs <= 150 -> SilverGray
                        else -> MutedGray
                    }
                    Text(
                        text = pingText,
                        color = pingColor,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp
                    )
                }
            } else {
                Text(
                    text = "TOCAR",
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp
                )
            }
        }
    }
}
