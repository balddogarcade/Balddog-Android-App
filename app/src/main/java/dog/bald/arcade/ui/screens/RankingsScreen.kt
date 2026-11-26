package dog.bald.arcade.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dog.bald.arcade.data.ArcadeRank
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.CountryCatalog
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.GameRankingSummaryDto
import dog.bald.arcade.data.GlobalLeaderboardEntryDto
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.ui.components.ArcadeBadge
import dog.bald.arcade.ui.components.ArcadeBadgeStyle
import dog.bald.arcade.ui.components.DiceBearPixelArtAvatar
import dog.bald.arcade.ui.components.GameCoverImage
import dog.bald.arcade.ui.components.RankingsMechanicsDialog
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

private enum class RankingsSubTab(
    val label: String,
) {
    GLOBAL("GLOBAL JUGADORES"),
    GAMES("POR JUEGO"),
}

@Composable
fun RankingsScreen(
    repo: ArcadeRepository,
    games: List<GameDto>,
    onOpenPublicProfile: (String) -> Unit,
    onOpenGameRanking: (GameDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSubTab by remember { mutableIntStateOf(RankingsSubTab.GLOBAL.ordinal) }
    var onlyFollowing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearch by remember { mutableStateOf("") }
    var showMechanicsDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var loadingGlobal by remember { mutableStateOf(true) }
    var globalErrorMsg by remember { mutableStateOf<String?>(null) }
    var loadingGames by remember { mutableStateOf(true) }
    var gamesErrorMsg by remember { mutableStateOf<String?>(null) }
    var globalEntries by remember { mutableStateOf<List<GlobalLeaderboardEntryDto>>(emptyList()) }
    var gameSummaries by remember { mutableStateOf<Map<String, GameRankingSummaryDto>>(emptyMap()) }

    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val gamesWithLeaderboard =
        remember(games) {
            games.filter { !it.scoreConfig?.lboard.isNullOrBlank() }
        }

    LaunchedEffect(searchQuery) {
        delay(300L)
        debouncedSearch = searchQuery.trim()
    }

    LaunchedEffect(refreshKey) {
        loadingGames = true
        gamesErrorMsg = null
        runCatching {
            repo.fetchGamesRankingSummary().associateBy { it.gameId }
        }.onSuccess { summaries ->
            gameSummaries = summaries
        }.onFailure {
            gamesErrorMsg = it.toUserMessage("No se pudieron cargar los rankings")
        }
        loadingGames = false
    }

    LaunchedEffect(refreshKey, onlyFollowing, debouncedSearch) {
        loadingGlobal = true
        globalErrorMsg = null
        runCatching {
            repo.fetchGlobalLeaderboard(
                onlyFollowing = onlyFollowing,
                search = debouncedSearch.takeIf { it.isNotBlank() },
                limit = 100,
                offset = 0,
            )
        }.onSuccess { gList ->
            globalEntries = gList
        }.onFailure {
            globalErrorMsg = it.toUserMessage("No se pudieron cargar los rankings")
        }
        loadingGlobal = false
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "RANKINGS 1CC",
                color = PureWhite,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier =
                        Modifier
                            .background(CarbonSurface, RectangleShape)
                            .border(1.dp, SteelBorder, RectangleShape)
                            .clickable { showMechanicsDialog = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Mecánicas",
                            tint = PureWhite,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "REGLAS",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .background(CarbonSurface, RectangleShape)
                            .border(1.dp, SteelBorder, RectangleShape)
                            .clickable { refreshKey++ }
                            .padding(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Actualizar",
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, CharcoalBorder, RectangleShape),
        ) {
            RankingsSubTab.entries.forEachIndexed { idx, tab ->
                val active = selectedSubTab == idx
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .background(if (active) PureWhite else CarbonSurface)
                            .clickable { selectedSubTab = idx }
                            .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        color = if (active) PureBlack else SilverGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (selectedSubTab == RankingsSubTab.GLOBAL.ordinal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    placeholder = { Text("Buscar jugador...", color = MutedGray, fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MutedGray,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedBorderColor = PureWhite,
                            unfocusedBorderColor = CharcoalBorder,
                            cursorColor = PureWhite,
                        ),
                    modifier = Modifier.weight(1f),
                )

                Row(
                    modifier =
                        Modifier
                            .background(ObsidianBlack, RectangleShape)
                            .border(1.dp, CharcoalBorder, RectangleShape),
                ) {
                    listOf(false to "TODOS", true to "SIGUIENDO").forEach { (flagVal, label) ->
                        val active = onlyFollowing == flagVal
                        Box(
                            modifier =
                                Modifier
                                    .background(if (active) PureWhite else CarbonSurface)
                                    .clickable { onlyFollowing = flagVal }
                                    .padding(horizontal = 10.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = label,
                                color = if (active) PureBlack else SilverGray,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }

        if (selectedSubTab == RankingsSubTab.GLOBAL.ordinal) {
            when {
                loadingGlobal -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PureWhite)
                    }
                }
                globalErrorMsg != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = globalErrorMsg!!, color = SilverGray, fontSize = 12.sp)
                    }
                }
                globalEntries.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (onlyFollowing) "Sin jugadores seguidos con récords 1CC." else "No se encontraron jugadores.",
                            color = MutedGray,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.sp,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(globalEntries, key = { it.userId }) { entry ->
                            GlobalRankRow(
                                entry = entry,
                                numberFmt = numberFmt,
                                onClick = { onOpenPublicProfile(entry.userId) },
                            )
                        }
                    }
                }
            }
        } else {
            when {
                loadingGames -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PureWhite)
                    }
                }
                gamesErrorMsg != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = gamesErrorMsg!!, color = SilverGray, fontSize = 12.sp)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(gamesWithLeaderboard, key = { it.id }) { game ->
                            GameRankingSummaryCard(
                                game = game,
                                summary = gameSummaries[game.id],
                                numberFmt = numberFmt,
                                onOpenGameRanking = { onOpenGameRanking(game) },
                                onOpenUserProfile = onOpenPublicProfile,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMechanicsDialog) {
        RankingsMechanicsDialog(onDismiss = { showMechanicsDialog = false })
    }
}

@Composable
private fun GlobalRankRow(
    entry: GlobalLeaderboardEntryDto,
    numberFmt: NumberFormat,
    onClick: () -> Unit,
) {
    val rank = ArcadeRank.fromPoints(entry.globalPoints)
    val flag = CountryCatalog.flagEmoji(entry.countryCode)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CarbonSurface, RectangleShape)
                .border(1.dp, CharcoalBorder, RectangleShape)
                .clickable(onClick = onClick)
                .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(38.dp)
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, SteelBorder, RectangleShape)
                    .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "#${entry.globalRank}",
                color = PureWhite,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }

        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, SteelBorder, RectangleShape)
                    .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            DiceBearPixelArtAvatar(
                seed = entry.avatarSeed?.takeIf { it.isNotBlank() } ?: entry.username,
                modifier = Modifier.size(34.dp),
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (flag.isNotEmpty()) Text(text = flag, fontSize = 13.sp)
                Text(
                    text = entry.username,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ArcadeBadge(text = rank.title, style = ArcadeBadgeStyle.Outline)
                Text(
                    text = "${entry.firstPlaces}×#1 · ${entry.podiums} podios",
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = numberFmt.format(entry.globalPoints),
                color = PureWhite,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                text = "PTS",
                color = MutedGray,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun GameRankingSummaryCard(
    game: GameDto,
    summary: GameRankingSummaryDto?,
    numberFmt: NumberFormat,
    onOpenGameRanking: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CarbonSurface, RectangleShape)
                .border(1.dp, CharcoalBorder, RectangleShape)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(54.dp)
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, SteelBorder, RectangleShape),
            ) {
                GameCoverImage(
                    slug = game.slug,
                    contentDescription = game.title,
                    showBorder = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = game.title,
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${game.system.uppercase()} · ${summary?.totalPlayers ?: 0} jugadores 1CC",
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                )
                if (summary?.myRank != null && summary.myScore != null) {
                    Text(
                        text = "TU PUESTO: #${summary.myRank} (${numberFmt.format(summary.myScore)})",
                        color = PureWhite,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .background(PureWhite, RectangleShape)
                        .clickable(onClick = onOpenGameRanking)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "VER RANKING",
                    color = PureBlack,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
        }

        val top3 = summary?.top3.orEmpty()
        if (top3.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape)
                        .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                top3.forEach { item ->
                    val flag = CountryCatalog.flagEmoji(item.countryCode)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUserProfile(item.userId) }
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "#${item.rank}  ${if (flag.isNotEmpty()) "$flag " else ""}${item.username}",
                            color = SilverGray,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = numberFmt.format(item.score),
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
