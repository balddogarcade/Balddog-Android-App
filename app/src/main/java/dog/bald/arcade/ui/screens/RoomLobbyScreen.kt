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
import androidx.compose.foundation.layout.padding
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
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeMagenta
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.CoinYellow
import dog.bald.arcade.ui.theme.DeepPurple
import dog.bald.arcade.ui.theme.LeatherBlack
import dog.bald.arcade.ui.theme.RetroIvory
import dog.bald.arcade.ui.theme.SteelGray
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

    val isHost = room.hostId == myUid
    val mySlot = room.slotsJson.entries.firstOrNull { it.value.userId == myUid }?.key?.toIntOrNull()

    // Si somos Host de una sala LAN, anunciamos por NsdManager
    DisposableEffect(room.id, isHost) {
        if (isHost && room.mode == "lan" && room.localPort != null) {
            lanNsd.registerRoom(room.code, game?.title ?: "Arcade", room.localPort ?: 7000)
        }
        onDispose {
            if (isHost && room.mode == "lan") {
                lanNsd.unregisterRoom()
            }
        }
    }

    // Polling ligero de sala y chat (complementa Realtime sin depender de configuración extra)
    LaunchedEffect(room.id) {
        while (true) {
            runCatching {
                repo.fetchRoomById(room.id)?.let { updated ->
                    room = updated
                    if (updated.status == "playing" && mySlot != null) {
                        onStartMatch(updated, mySlot)
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
            .background(ArcadeBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SALA #${room.code} · ${room.mode.uppercase()}",
                    color = CoinYellow,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = game?.title ?: "Arcade Match",
                    color = ArcadeMagenta,
                    fontSize = 13.sp
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (isHost) runCatching { repo.closeRoom(room.id) }
                        onLeave()
                    }
                },
                border = BorderStroke(1.dp, ArcadeOrange)
            ) {
                Text("SALIR", color = ArcadeOrange, fontSize = 12.sp)
            }
        }

        if (errorMsg != null) {
            Text(errorMsg ?: "", color = ArcadeOrange, fontSize = 12.sp)
        }

        // Slots 1P..4P
        Text("SLOTS DE JUGADORES (Toca un slot libre):", color = SteelGray, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (slot in 0 until room.maxPlayers) {
                val occupant = room.slotsJson[slot.toString()]
                val isMine = occupant?.userId == myUid
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMine) DeepPurple else LeatherBlack
                    ),
                    border = BorderStroke(
                        1.dp,
                        when {
                            occupant == null -> SteelGray.copy(alpha = 0.4f)
                            occupant.ready -> CoinYellow
                            else -> ArcadeMagenta
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
                        Text("${slot + 1}P", color = CoinYellow, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        Text(
                            text = occupant?.username ?: "LIBRE",
                            color = if (occupant != null) RetroIvory else SteelGray,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        if (occupant != null) {
                            Text(
                                text = if (occupant.ready) "READY" else "...",
                                color = if (occupant.ready) CoinYellow else ArcadeOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                    border = BorderStroke(1.dp, ArcadeMagenta)
                ) {
                    Text(sticker, color = RetroIvory, fontSize = 11.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(LeatherBlack, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(messages, key = { it.id }) { msg ->
                    Text(
                        text = "${msg.username}: ${msg.message}",
                        color = RetroIvory,
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
                    focusedBorderColor = ArcadeMagenta,
                    unfocusedBorderColor = SteelGray,
                    focusedTextColor = RetroIvory,
                    unfocusedTextColor = RetroIvory
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
                colors = ButtonDefaults.buttonColors(containerColor = ArcadeMagenta, contentColor = RetroIvory)
            ) {
                Text("ENVIAR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (!isHost && mySlot != null) {
                Button(
                    onClick = { toggleReady() },
                    colors = ButtonDefaults.buttonColors(containerColor = ArcadeOrange, contentColor = ArcadeBlack),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("LISTO / READY", fontWeight = FontWeight.ExtraBold)
                }
            }
            if (isHost && mySlot != null) {
                val seated = room.slotsJson.values
                val canStart = seated.size >= 2 && seated.all { it.ready }
                Button(
                    enabled = canStart,
                    onClick = {
                        scope.launch {
                            runCatching {
                                repo.updateRoomSlots(room.id, room.slotsJson, status = "playing")
                                onStartMatch(room.copy(status = "playing"), mySlot)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoinYellow, contentColor = ArcadeBlack),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (canStart) "INICIAR PARTIDA" else "ESPERANDO JUGADORES (${seated.size}/2+)",
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
