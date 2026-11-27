package dog.bald.arcade.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.MatchmakingStatsDto
import dog.bald.arcade.net.MatchmakingPolicy
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.ReadyGreenAccent
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder

@Composable
fun QuickMatchmakingCard(
    games: List<GameDto>,
    selectedGame: GameDto?,
    stats: MatchmakingStatsDto?,
    isSearching: Boolean,
    elapsedSec: Long,
    onSelectGame: (GameDto) -> Unit,
    onStartSearch: (GameDto) -> Unit,
    onCancelSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGamePicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val sortedGames =
        remember(games) {
            games.sortedBy { (it.shortTitle ?: it.title).lowercase() }
        }
    val statsBySlug =
        remember(stats) {
            stats?.byGame?.associateBy { it.gameSlug }.orEmpty()
        }
    val activeGame = selectedGame ?: sortedGames.firstOrNull()
    val activeStat = activeGame?.let { statsBySlug[it.slug] }

    Card(
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, if (isSearching) ReadyGreenAccent else SteelBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "EMPAREJAMIENTO RÁPIDO",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    text =
                        "${stats?.totalSearching ?: 0} buscando · ${stats?.totalPlaying ?: 0} jugando",
                    color = SilverGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                )
            }

            OutlinedButton(
                enabled = !isSearching && sortedGames.isNotEmpty(),
                onClick = {
                    searchQuery = ""
                    showGamePicker = true
                },
                shape = RectangleShape,
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeGame?.let { it.shortTitle ?: it.title } ?: "Seleccionar juego",
                            color = PureWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (activeGame != null) {
                            val sCount = activeStat?.searching ?: 0
                            val pCount = activeStat?.playing ?: 0
                            Text(
                                text = "Hasta ${activeGame.maxPlayers}P · $sCount buscando · $pCount jugando",
                                color = MutedGray,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "Elegir juego",
                        tint = SilverGray,
                    )
                }
            }

            if (isSearching) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onCancelSearch,
                        shape = RectangleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = ObsidianBlack,
                                contentColor = PureWhite,
                            ),
                        border = BorderStroke(1.dp, ReadyGreenAccent),
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(44.dp),
                    ) {
                        CircularProgressIndicator(
                            color = ReadyGreenAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = ReadyGreenAccent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "BUSCANDO... ${MatchmakingPolicy.formatElapsedMmSs(elapsedSec)} · CANCELAR",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }
                if (elapsedSec >= 120L) {
                    Text(
                        text = "Poca concurrencia ahora mismo. Seguimos buscando o puedes invitar con Link Mágico.",
                        color = SilverGray,
                        fontSize = 11.sp,
                    )
                }
            } else {
                Button(
                    enabled = activeGame != null,
                    onClick = { activeGame?.let(onStartSearch) },
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = PureWhite,
                            contentColor = PureBlack,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BUSCAR PARTIDA RÁPIDA",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    if (showGamePicker) {
        val filtered =
            remember(sortedGames, searchQuery) {
                val q = searchQuery.trim().lowercase()
                if (q.isEmpty()) {
                    sortedGames
                } else {
                    sortedGames.filter {
                        it.title.lowercase().contains(q) ||
                            (it.shortTitle?.lowercase()?.contains(q) == true) ||
                            it.slug.lowercase().contains(q)
                    }
                }
            }

        AlertDialog(
            onDismissRequest = { showGamePicker = false },
            containerColor = GraphiteSurface,
            title = {
                Text(
                    text = "Elegir juego (A–Z)",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filtrar juego...", color = MutedGray, fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MutedGray)
                        },
                        singleLine = true,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = PureWhite,
                                unfocusedBorderColor = CharcoalBorder,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            val stat = statsBySlug[item.slug]
                            val searchingCount = stat?.searching ?: 0
                            val playingCount = stat?.playing ?: 0
                            val isSelected = item.id == activeGame?.id
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelectGame(item)
                                            showGamePicker = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.shortTitle ?: item.title,
                                        color = if (isSelected) ReadyGreenAccent else PureWhite,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${item.maxPlayers}P max · $playingCount jugando",
                                        color = MutedGray,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                    )
                                }
                                Text(
                                    text = "$searchingCount buscando",
                                    color = if (searchingCount > 0) ReadyGreenAccent else MutedGray,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGamePicker = false }) {
                    Text("CERRAR", color = PureWhite)
                }
            },
        )
    }
}

@Composable
fun MatchmakingMiniBanner(
    gameTitle: String,
    elapsedSec: Long,
    onOpenRoomsTab: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = ObsidianBlack),
        border = BorderStroke(1.dp, ReadyGreenAccent),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRoomsTab),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                CircularProgressIndicator(
                    color = ReadyGreenAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Column {
                    Text(
                        text = "Buscando: $gameTitle",
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Tiempo ${MatchmakingPolicy.formatElapsedMmSs(elapsedSec)} · Toca para ver",
                        color = ReadyGreenAccent,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancelar búsqueda",
                    tint = PureWhite,
                )
            }
        }
    }
}
