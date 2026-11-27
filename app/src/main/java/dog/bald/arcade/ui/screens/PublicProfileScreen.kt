package dog.bald.arcade.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dog.bald.arcade.data.PublicProfileDto
import dog.bald.arcade.data.computeArcadeBadges
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.ui.components.ArcadeBadge
import dog.bald.arcade.ui.components.ArcadeBadgeStyle
import dog.bald.arcade.ui.components.ArcadeBadgesStrip
import dog.bald.arcade.ui.components.DiceBearPixelArtAvatar
import dog.bald.arcade.ui.components.SocialLinksBar
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
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@Composable
fun PublicProfileScreen(
    userId: String,
    repo: ArcadeRepository,
    games: List<GameDto>,
    onBack: () -> Unit,
    onOpenGameRanking: (GameDto) -> Unit,
    onOpenFollowList: (targetUserId: String, direction: String, username: String) -> Unit,
    onFollowChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val myUserId = remember { repo.currentUserId() }
    val gamesById = remember(games) { games.associateBy { it.id } }
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }

    var loading by remember(userId) { mutableStateOf(true) }
    var errorMsg by remember(userId) { mutableStateOf<String?>(null) }
    var profile by remember(userId) { mutableStateOf<PublicProfileDto?>(null) }
    var togglingFollow by remember(userId) { mutableStateOf(false) }

    LaunchedEffect(userId) {
        loading = true
        errorMsg = null
        runCatching { repo.fetchPublicProfile(userId) }
            .onSuccess {
                profile = it
                if (it == null) errorMsg = "Perfil no encontrado"
            }.onFailure {
                errorMsg = it.toUserMessage("No se pudo cargar el perfil")
            }
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                text = "PERFIL PÚBLICO",
                color = PureWhite,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PureWhite)
                }
            }
            profile == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = errorMsg ?: "Perfil no disponible",
                        color = SilverGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                    )
                }
            }
            else -> {
                val p = profile!!
                val isOwn = p.id == myUserId
                val hidePrivate = p.isPrivate && !isOwn
                val rank = ArcadeRank.fromPoints(p.globalPoints)
                val flag = CountryCatalog.flagEmoji(p.countryCode)
                val badges =
                    remember(p) {
                        computeArcadeBadges(
                            firstPlaces = p.firstPlaces,
                            podiums = p.podiums,
                            total1cc = p.total1cc,
                            verifiedReplays = p.verifiedReplays,
                            totalPlaySeconds = p.totalPlaySeconds,
                            netplayMatches = p.netplayMatches,
                        )
                    }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(CarbonSurface, RectangleShape)
                                    .border(1.dp, CharcoalBorder, RectangleShape)
                                    .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(60.dp)
                                            .background(ObsidianBlack, RectangleShape)
                                            .border(1.dp, SteelBorder, RectangleShape)
                                            .padding(4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    DiceBearPixelArtAvatar(
                                        seed = p.avatarSeed?.takeIf { it.isNotBlank() } ?: p.username,
                                        modifier = Modifier.size(52.dp),
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (flag.isNotEmpty()) {
                                            Text(text = flag, fontSize = 16.sp)
                                        }
                                        Text(
                                            text = p.username,
                                            color = PureWhite,
                                            fontFamily = SpaceGroteskFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        ArcadeBadge(text = rank.title, style = ArcadeBadgeStyle.Solid)
                                        Text(
                                            text =
                                                buildString {
                                                    append("${p.globalPoints} PTS")
                                                    p.globalRank?.let { append(" · #$it") }
                                                },
                                            color = SilverGray,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }

                            if (!p.bio.isNullOrBlank()) {
                                Text(
                                    text = p.bio,
                                    color = SilverGray,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .background(ObsidianBlack, RectangleShape)
                                            .border(1.dp, CharcoalBorder, RectangleShape)
                                            .clickable { onOpenFollowList(p.id, "followers", p.username) }
                                            .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "SEGUIDORES (${p.followersCount})",
                                        color = PureWhite,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .background(ObsidianBlack, RectangleShape)
                                            .border(1.dp, CharcoalBorder, RectangleShape)
                                            .clickable { onOpenFollowList(p.id, "following", p.username) }
                                            .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "SIGUIENDO (${p.followingCount})",
                                        color = PureWhite,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }
                            }

                            if (!isOwn) {
                                val following = p.isFollowingByMe
                                if (following) {
                                    OutlinedButton(
                                        enabled = !togglingFollow,
                                        shape = RectangleShape,
                                        border = BorderStroke(1.dp, SteelBorder),
                                        onClick = {
                                            val prev = p
                                            profile =
                                                p.copy(
                                                    isFollowingByMe = false,
                                                    followersCount = (p.followersCount - 1L).coerceAtLeast(0L),
                                                )
                                            scope.launch {
                                                togglingFollow = true
                                                runCatching { repo.unfollowUser(p.id) }
                                                    .onSuccess {
                                                        dog.bald.arcade.diagnostics.AnalyticsHelper.logFollowToggled(false, "public_profile")
                                                    }
                                                    .onSuccess { onFollowChanged() }
                                                    .onFailure { profile = prev }
                                                togglingFollow = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = "SIGUIENDO",
                                            color = PureWhite,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                        )
                                    }
                                } else {
                                    Button(
                                        enabled = !togglingFollow,
                                        shape = RectangleShape,
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = PureWhite,
                                                contentColor = PureBlack,
                                            ),
                                        onClick = {
                                            val prev = p
                                            profile =
                                                p.copy(
                                                    isFollowingByMe = true,
                                                    followersCount = p.followersCount + 1L,
                                                )
                                            scope.launch {
                                                togglingFollow = true
                                                runCatching { repo.followUser(p.id) }
                                                    .onSuccess {
                                                        dog.bald.arcade.diagnostics.AnalyticsHelper.logFollowToggled(true, "public_profile")
                                                    }
                                                    .onSuccess { onFollowChanged() }
                                                    .onFailure { profile = prev }
                                                togglingFollow = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = "SEGUIR",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }

                            if (hidePrivate) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .background(ObsidianBlack, RectangleShape)
                                            .border(1.dp, CharcoalBorder, RectangleShape)
                                            .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MutedGray,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Este jugador ocultó sus redes sociales, tiempo e historial.",
                                        color = MutedGray,
                                        fontSize = 11.sp,
                                    )
                                }
                            } else {
                                SocialLinksBar(links = p.socialLinks)
                                HorizontalDivider(color = CharcoalBorder)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    PublicStatCell("TIEMPO", formatPlayDuration(p.totalPlaySeconds ?: 0L))
                                    PublicStatCell("PARTIDAS", "${p.totalPlays ?: 0}")
                                    PublicStatCell("1CC", "${p.total1cc}")
                                    PublicStatCell("PODIOS", "${p.podiums}")
                                }
                            }

                            if (badges.isNotEmpty()) {
                                HorizontalDivider(color = CharcoalBorder)
                                ArcadeBadgesStrip(badges = badges)
                            }
                        }
                    }

                    item(key = "records_header") {
                        Text(
                            text = "RÉCORDS 1CC (${p.highScores.size})",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }

                    if (p.highScores.isEmpty()) {
                        item(key = "no_records") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(CarbonSurface, RectangleShape)
                                        .border(1.dp, CharcoalBorder, RectangleShape)
                                        .padding(16.dp),
                            ) {
                                Text(
                                    text = "Sin récords 1CC registrados aún.",
                                    color = MutedGray,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    } else {
                        items(p.highScores, key = { "pub_rec_${it.gameId}" }) { rec ->
                            val game = gamesById[rec.gameId]
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(CarbonSurface, RectangleShape)
                                        .border(1.dp, CharcoalBorder, RectangleShape)
                                        .clickable(enabled = game != null) {
                                            if (game != null) onOpenGameRanking(game)
                                        }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = game?.title ?: "Juego",
                                        color = PureWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "#${rec.rank} de ${rec.totalPlayers} · ${if (rec.verificationStatus == "verified") "REPLAY VERIFICADO" else "1CC"}",
                                        color = MutedGray,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = numberFmt.format(rec.score),
                                        color = PureWhite,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                    Icon(
                                        imageVector = Icons.Default.EmojiEvents,
                                        contentDescription = null,
                                        tint = SilverGray,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (!hidePrivate && p.recentMatches.isNotEmpty()) {
                        item(key = "history_header") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "HISTORIAL RECIENTE (${p.recentMatches.size})",
                                color = PureWhite,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                        items(p.recentMatches, key = { "pub_match_${it.id}" }) { match ->
                            val game = gamesById[match.gameId]
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(GraphiteSurface, RectangleShape)
                                        .border(1.dp, CharcoalBorder, RectangleShape)
                                        .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = game?.title ?: "Partida",
                                        color = PureWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${match.mode.uppercase()} · ${formatPlayDuration(match.durationSeconds.toLong())}",
                                        color = MutedGray,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                    )
                                }
                                if (match.score != null && match.score > 0L) {
                                    Text(
                                        text = numberFmt.format(match.score),
                                        color = SilverGray,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicStatCell(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = PureWhite,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Text(
            text = label,
            color = MutedGray,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
        )
    }
}
