package dog.bald.arcade.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.GameLeaderboardData
import dog.bald.arcade.data.LeaderboardEntryDto
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLeaderboardSheet(
    game: GameDto,
    repo: ArcadeRepository,
    onWatchReplay: (LeaderboardEntryDto) -> Unit = {},
    onOpenUserProfile: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var refreshKey by remember(game.id) { mutableIntStateOf(0) }
    var loading by remember(game.id) { mutableStateOf(true) }
    var errorMsg by remember(game.id) { mutableStateOf<String?>(null) }
    var data by remember(game.id) { mutableStateOf(GameLeaderboardData()) }
    val currentUid = remember { repo.currentUserId() }
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val hasLeaderboard = !game.scoreConfig?.lboard.isNullOrBlank()

    LaunchedEffect(game.id, refreshKey, hasLeaderboard) {
        if (!hasLeaderboard) {
            loading = false
            errorMsg = null
            data = GameLeaderboardData()
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        runCatching {
            coroutineScope {
                val topDeferred = async { repo.fetchGameLeaderboard(game.id, limit = 50, offset = 0) }
                val myDeferred = async { runCatching { repo.fetchMyGameRank(game.id) }.getOrNull() }
                GameLeaderboardData(
                    entries = topDeferred.await(),
                    myEntry = myDeferred.await()
                )
            }
        }.onSuccess {
            data = it
            loading = false
        }.onFailure { err ->
            errorMsg = err.toUserMessage("No se pudo cargar el ranking")
            loading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianBlack,
        contentColor = PureWhite,
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "TOP RANKING · 1 CRÉDITO",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SilverGray
                        )
                        Text(
                            text = game.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = { if (!loading) refreshKey++ }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Actualizar ranking",
                        tint = SilverGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 1.dp, color = CharcoalBorder)
            Spacer(modifier = Modifier.height(12.dp))

            when {
                !hasLeaderboard -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(CarbonSurface)
                            .border(1.dp, CharcoalBorder, RectangleShape)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Modo Libre: el ranking 1-Crédito y replays para este título estarán disponibles próximamente.\nTu tiempo jugado e historial de partidas sí se registran.",
                            color = SilverGray,
                            fontSize = 13.sp
                        )
                    }
                }

                loading -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        CircularProgressIndicator(color = PureWhite, strokeWidth = 2.dp)
                    }
                }

                errorMsg != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        Text(text = errorMsg ?: "", color = SilverGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { refreshKey++ },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PureWhite,
                                contentColor = PureBlack
                            )
                        ) {
                            Text("REINTENTAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                data.entries.isEmpty() -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(CarbonSurface)
                            .border(1.dp, CharcoalBorder, RectangleShape)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Aún no hay récords registrados.\n¡Sé el primero en marcar historia!",
                            color = SilverGray,
                            fontSize = 13.sp
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(data.entries, key = { it.userId }) { entry ->
                            LeaderboardRow(
                                entry = entry,
                                isMe = entry.userId == currentUid,
                                formattedScore = numberFmt.format(entry.score),
                                onClick = { onOpenUserProfile(entry.userId) },
                                onWatchReplay = if (!entry.replayPath.isNullOrBlank()) {
                                    { onWatchReplay(entry) }
                                } else null
                            )
                        }
                    }
                }
            }

            val myEntry = data.myEntry
            if (!loading && errorMsg == null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(thickness = 1.dp, color = CharcoalBorder)
                Spacer(modifier = Modifier.height(10.dp))

                if (myEntry != null) {
                    Text(
                        text = "TU POSICIÓN ACTUAL",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SilverGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LeaderboardRow(
                        entry = myEntry,
                        isMe = true,
                        formattedScore = numberFmt.format(myEntry.score),
                        onClick = { onOpenUserProfile(myEntry.userId) }
                    )
                    if (myEntry.isAnonymous) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Juegas como invitado: vincula un correo en Perfil para no perder tu récord.",
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    Text(
                        text = "Todavía no tienes puntaje en este juego. Juega en modo Solo (1 crédito) para entrar al ranking.",
                        color = MutedGray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(
    entry: LeaderboardEntryDto,
    isMe: Boolean,
    formattedScore: String,
    onClick: () -> Unit = {},
    onWatchReplay: (() -> Unit)? = null
) {
    val bgColor = if (isMe) GraphiteSurface else CarbonSurface
    val borderColor = if (isMe || entry.rank <= 3L) SteelBorder else CharcoalBorder
    val rankColor = when (entry.rank) {
        1L -> Color(0xFFFFD54F)
        2L -> Color(0xFFE0E0E0)
        3L -> Color(0xFFFFAB91)
        else -> SilverGray
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .border(1.dp, borderColor, RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "#${entry.rank}",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = rankColor,
            modifier = Modifier.width(38.dp)
        )
        DiceBearPixelArtAvatar(
            seed = entry.username,
            modifier = Modifier
                .size(26.dp)
                .border(1.dp, CharcoalBorder, RectangleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.username,
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isMe) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "TÚ",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureBlack,
                        modifier = Modifier
                            .background(PureWhite)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                when (entry.verificationStatus) {
                    "verified" -> {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "✓ VERIF.",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81C784),
                            modifier = Modifier
                                .border(1.dp, Color(0xFF388E3C), RectangleShape)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    "pending", "verifying" -> {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AUDITORÍA",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = SilverGray,
                            modifier = Modifier
                                .border(1.dp, CharcoalBorder, RectangleShape)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formattedScore,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = PureWhite
        )
        if (onWatchReplay != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(PureWhite)
                    .clickable(onClick = onWatchReplay)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Ver Replay",
                    tint = PureBlack,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "REPLAY",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureBlack
                )
            }
        }
    }
}
