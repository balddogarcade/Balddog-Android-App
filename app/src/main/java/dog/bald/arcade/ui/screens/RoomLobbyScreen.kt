package dog.bald.arcade.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.RoomDto
import dog.bald.arcade.data.RoomMessageDto
import dog.bald.arcade.data.RoomSlotInfo
import dog.bald.arcade.net.LanNsdManager
import dog.bald.arcade.ui.components.GameCoverImage
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RoomLobbyScreen(
    repo: ArcadeRepository,
    lanNsd: LanNsdManager,
    initialRoom: RoomDto,
    game: GameDto?,
    myUsername: String,
    myEndpoint: String,
    onLeave: () -> Unit,
    onStartMatch: (room: RoomDto, mySlot: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myUid = repo.currentUserId().orEmpty()

    var room by remember { mutableStateOf(initialRoom) }
    var messages by remember { mutableStateOf<List<RoomMessageDto>>(emptyList()) }
    var chatInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var matchLaunched by remember { mutableStateOf(false) }

    val isHost = room.hostId == myUid
    val mySlot = room.slotsJson.entries.firstOrNull { it.value.userId == myUid }?.key?.toIntOrNull()

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

    // Polling ligero de sala y chat (con guardia de disparo único para iniciar partida)
    LaunchedEffect(room.id) {
        while (!matchLaunched) {
            runCatching {
                repo.fetchRoomById(room.id)?.let { updated ->
                    room = updated
                    val updatedSlot = updated.slotsJson.entries
                        .firstOrNull { it.value.userId == myUid }
                        ?.key
                        ?.toIntOrNull()
                    if (updated.status == "playing" && updatedSlot != null && !matchLaunched) {
                        matchLaunched = true
                        onStartMatch(updated, updatedSlot)
                    }
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
                    ready = isHost
                )
                repo.updateRoomSlots(room.id, next)
                room = room.copy(slotsJson = next)
            } catch (t: Throwable) {
                errorMsg = t.message
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
                errorMsg = t.message
            }
        }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                GameCoverImage(
                    slug = game?.slug,
                    contentDescription = game?.title,
                    modifier = Modifier.size(48.dp)
                )
                Column {
                    val privacyLabel = if (room.isPrivate) " · PRIVADA" else ""
                    Text(
                        text = "SALA #${room.code} · ${room.mode.uppercase()}$privacyLabel",
                        color = PureWhite,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = game?.title ?: "Arcade Match",
                        color = MutedGray,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (isHost) runCatching { repo.closeRoom(room.id) }
                        onLeave()
                    }
                },
                border = BorderStroke(1.dp, SteelBorder)
            ) {
                Text("SALIR", color = SilverGray, fontSize = 12.sp)
            }
        }

        if (errorMsg != null) {
            Text(errorMsg ?: "", color = SilverGray, fontSize = 12.sp)
        }

        // Slots 1P..4P (en grilla de 2 columnas si son 3 o 4 jugadores)
        Text("SLOTS DE JUGADORES (${room.maxPlayers}P · Toca un slot libre):", color = MutedGray, fontSize = 11.sp)
        val slotIndices = (0 until room.maxPlayers).toList()
        val rows = slotIndices.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            rows.forEach { rowSlots ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowSlots.forEach { slot ->
                        val occupant = room.slotsJson[slot.toString()]
                        val isMine = occupant?.userId == myUid
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isMine) GraphiteSurface else CarbonSurface
                            ),
                            border = BorderStroke(
                                1.dp,
                                when {
                                    occupant == null -> CharcoalBorder
                                    occupant.ready -> PureWhite
                                    else -> SteelBorder
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = occupant == null) { claimSlot(slot) }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("${slot + 1}P", color = PureWhite, fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    text = occupant?.username ?: "LIBRE",
                                    color = if (occupant != null) PureWhite else MutedGray,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                                if (occupant != null) {
                                    Text(
                                        text = if (occupant.ready) "READY" else "...",
                                        color = if (occupant.ready) PureWhite else MutedGray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    if (rowSlots.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
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
                .background(CarbonSurface, RoundedCornerShape(10.dp))
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
