package dog.bald.arcade.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import dog.bald.arcade.ui.components.shareReplayLink
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.CountryCatalog
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.GameLeaderboardV2EntryDto
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.ui.components.DiceBearPixelArtAvatar
import dog.bald.arcade.ui.components.GameCoverImage
import dog.bald.arcade.ui.components.formatPlayDuration
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
import java.text.NumberFormat
import java.util.Locale

@Composable
fun GameRankingScreen(
    game: GameDto,
    repo: ArcadeRepository,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onBack: () -> Unit,
    onPlayOrDownload: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
    replayLaunchState: ReplayLaunchState? = null,
    onWatchReplay: (GameLeaderboardV2EntryDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val myUserId = remember { repo.currentUserId() }
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    var mode by remember(game.id) { mutableStateOf("1cc") }
    var onlyFollowing by remember(game.id) { mutableStateOf(false) }
    var limit by remember(game.id) { mutableIntStateOf(50) }
    var refreshKey by remember(game.id) { mutableIntStateOf(0) }

    var loading by remember(game.id) { mutableStateOf(true) }
    var loadingReplayUserId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember(game.id) { mutableStateOf<String?>(null) }
    var entries by remember(game.id) { mutableStateOf<List<GameLeaderboardV2EntryDto>>(emptyList()) }

    LaunchedEffect(game.id, mode, onlyFollowing, limit, refreshKey) {
        loading = true
        errorMsg = null
        runCatching {
            repo.fetchGameLeaderboardV2(
                gameId = game.id,
                mode = mode,
                onlyFollowing = onlyFollowing,
                limit = limit,
                offset = 0,
            )
        }.onSuccess { entries = it }
            .onFailure { errorMsg = it.toUserMessage("No se pudo cargar el ranking") }
        loading = false
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(ObsidianBlack)
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(PureBlack)
                    .border(1.dp, CharcoalBorder, RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier =
                        Modifier
                            .background(CarbonSurface, RectangleShape)
                            .border(1.dp, SteelBorder, RectangleShape)
                            .clickable(onClick = onBack)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = PureWhite,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "VOLVER",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    text = game.title,
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
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

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.title,
                        color = PureWhite,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${game.system.uppercase()} · RANKING INDIVIDUAL",
                        color = MutedGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                    )
                }

                Button(
                    enabled = !isDownloading,
                    onClick = onPlayOrDownload,
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = PureWhite,
                            contentColor = PureBlack,
                        ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text =
                            when {
                                isDownloading -> "${(downloadProgress * 100).toInt()}%"
                                isDownloaded -> "JUGAR"
                                else -> "DESCARGAR"
                            },
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
            }

            if (isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Descargando ROM/BIOS por IPFS... ${(downloadProgress * 100).toInt()}%",
                        color = SilverGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                    )
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        color = PureWhite,
                        trackColor = ObsidianBlack,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape),
            ) {
                listOf("1cc" to "TOP 1CC", "playtime" to "TIEMPO").forEach { (m, label) ->
                    val active = mode == m
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .background(if (active) PureWhite else CarbonSurface)
                                .clickable {
                                    mode = m
                                    limit = 50
                                }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
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
                                .padding(horizontal = 10.dp, vertical = 10.dp),
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

        when {
            loading && entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PureWhite)
                }
            }
            errorMsg != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(text = errorMsg!!, color = SilverGray, fontSize = 12.sp)
                }
            }
            entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (onlyFollowing) "Sin jugadores seguidos en este ranking." else "Aún no hay registros en este ranking.",
                        color = MutedGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { "${mode}_${it.userId}" }) { entry ->
                        val isMe = entry.userId == myUserId
                        val flag = CountryCatalog.flagEmoji(entry.countryCode)
                        val rankColor =
                            when (entry.rank) {
                                1L -> Color(0xFFFFD54F)
                                2L -> Color(0xFFE0E0E0)
                                3L -> Color(0xFFFFAB91)
                                else -> SilverGray
                            }
                        val itemLaunchState = replayLaunchState?.takeIf { it.replayPath == entry.replayPath }
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (isMe) GraphiteSurface else CarbonSurface, RectangleShape)
                                    .border(1.dp, if (isMe || entry.rank <= 3L) SteelBorder else CharcoalBorder, RectangleShape)
                                    .clickable { onOpenUserProfile(entry.userId) },
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#${entry.rank}",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = rankColor,
                                    modifier = Modifier.width(38.dp),
                                )
                                DiceBearPixelArtAvatar(
                                    seed = entry.avatarSeed?.takeIf { it.isNotBlank() } ?: entry.username,
                                    modifier =
                                        Modifier
                                            .size(30.dp)
                                            .border(1.dp, CharcoalBorder, RectangleShape),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (flag.isNotEmpty()) {
                                            Text(text = "$flag ", fontSize = 12.sp)
                                        }
                                        Text(
                                            text = entry.username,
                                            color = PureWhite,
                                            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text =
                                            if (mode == "1cc") {
                                                buildString {
                                                    append(formatPlayDuration(entry.playSeconds))
                                                    if (entry.verificationStatus == "verified") append(" · ✓ VERIFICADO")
                                                }
                                            } else {
                                                "Tiempo acumulado"
                                            },
                                        color = MutedGray,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                    )
                                }
                                Text(
                                    text = if (mode == "1cc") numberFmt.format(entry.score) else formatPlayDuration(entry.score),
                                    color = PureWhite,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                if (mode == "1cc" && !entry.replayPath.isNullOrBlank()) {
                                    if (!entry.id.isNullOrBlank()) {
                                        val clipboardManager = LocalClipboardManager.current
                                        val context = LocalContext.current
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString("https://bald.dog/replays/${entry.id}"))
                                                Toast.makeText(context, "Enlace de repetición copiado", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ContentCopy,
                                                contentDescription = "Copiar enlace de repetición",
                                                tint = SilverGray,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                shareReplayLink(
                                                    context = context,
                                                    replayId = entry.id,
                                                    gameTitle = game.title,
                                                    formattedScore = numberFmt.format(entry.score),
                                                )
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Share,
                                                contentDescription = "Compartir enlace de repetición",
                                                tint = SilverGray,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    val isReplayLoading = itemLaunchState != null
                                    IconButton(
                                        onClick = {
                                            if (replayLaunchState == null) {
                                                onWatchReplay(entry)
                                            }
                                        },
                                        enabled = replayLaunchState == null,
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        if (isReplayLoading) {
                                            CircularProgressIndicator(
                                                color = PureWhite,
                                                strokeWidth = 1.5.dp,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Ver replay",
                                                tint = PureWhite,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (itemLaunchState != null) {
                                val pctText = if (itemLaunchState.progress >= 0f) " ${(itemLaunchState.progress * 100).toInt()}%" else ""
                                Text(
                                    text = "${itemLaunchState.stage}$pctText",
                                    color = SilverGray,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                                )
                                if (itemLaunchState.progress >= 0f) {
                                    LinearProgressIndicator(
                                        progress = { itemLaunchState.progress.coerceIn(0f, 1f) },
                                        color = PureWhite,
                                        trackColor = GraphiteSurface,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(3.dp),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        color = PureWhite,
                                        trackColor = GraphiteSurface,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(3.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (entries.size >= limit && limit < 100) {
                        item(key = "load_more") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(GraphiteSurface, RectangleShape)
                                        .border(1.dp, SteelBorder, RectangleShape)
                                        .clickable { limit = (limit + 50).coerceAtMost(100) }
                                        .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "CARGAR MÁS",
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
    }
}
