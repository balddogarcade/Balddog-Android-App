package dog.bald.arcade.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.LocalContext
import dog.bald.arcade.data.ArcadeBadge
import dog.bald.arcade.data.ArcadeRank
import dog.bald.arcade.data.CountryCatalog
import dog.bald.arcade.data.PublicProfileDto
import dog.bald.arcade.data.SocialLinksDto
import dog.bald.arcade.data.SocialValidation
import dog.bald.arcade.data.computeArcadeBadges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.MatchHistoryDto
import dog.bald.arcade.data.MyHighScoreDto
import dog.bald.arcade.data.ProfileDto
import dog.bald.arcade.data.UserGameStatDto
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.DangerRed
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
fun ArcadeAvatarOrPlaceholder(
    avatarSeed: String?,
    modifier: Modifier = Modifier
) {
    val cleanSeed = avatarSeed?.takeIf { it.isNotBlank() }
    if (cleanSeed != null) {
        DiceBearPixelArtAvatar(
            seed = cleanSeed,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .background(ObsidianBlack, RectangleShape)
                .border(1.dp, SteelBorder, RectangleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MutedGray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ProfileHeroCard(
    profile: ProfileDto?,
    userEmail: String?,
    avatarSeed: String?,
    recordsCount: Int,
    gamesPlayedCount: Int,
    onEditUsername: () -> Unit,
    publicProfile: PublicProfileDto? = null,
    onOpenFollowers: () -> Unit = {},
    onOpenFollowing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val effectiveSeed = profile?.avatarSeed?.takeIf { it.isNotBlank() }
        ?: avatarSeed?.takeIf { it.isNotBlank() }
    val globalPoints = publicProfile?.globalPoints ?: 0L
    val rank = ArcadeRank.fromPoints(globalPoints)
    val flag = CountryCatalog.flagEmoji(profile?.countryCode)
    val badges = remember(publicProfile, profile, recordsCount) {
        computeArcadeBadges(
            firstPlaces = publicProfile?.firstPlaces ?: 0L,
            podiums = publicProfile?.podiums ?: 0L,
            total1cc = publicProfile?.total1cc ?: recordsCount.toLong(),
            verifiedReplays = publicProfile?.verifiedReplays ?: 0L,
            totalPlaySeconds = profile?.totalPlaySeconds,
            netplayMatches = publicProfile?.netplayMatches ?: 0L
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CarbonSurface, RectangleShape)
            .border(1.dp, CharcoalBorder, RectangleShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, SteelBorder, RectangleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                ArcadeAvatarOrPlaceholder(
                    avatarSeed = effectiveSeed,
                    modifier = Modifier.size(52.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (flag.isNotEmpty()) {
                        Text(text = flag, fontSize = 16.sp)
                    }
                    Text(
                        text = profile?.username ?: "Cargando...",
                        color = PureWhite,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ArcadeBadge(
                        text = rank.title,
                        style = ArcadeBadgeStyle.Solid
                    )
                    Text(
                        text = buildString {
                            append("$globalPoints PTS")
                            publicProfile?.globalRank?.let { append(" · #$it") }
                        },
                        color = SilverGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    if (profile?.isPrivate == true) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Perfil privado",
                            tint = MutedGray,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                if (!userEmail.isNullOrBlank()) {
                    Text(
                        text = userEmail,
                        color = MutedGray,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (!profile?.bio.isNullOrBlank()) {
            Text(
                text = profile?.bio.orEmpty(),
                color = SilverGray,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, CharcoalBorder, RectangleShape)
                    .clickable(onClick = onOpenFollowers)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SEGUIDORES (${publicProfile?.followersCount ?: 0})",
                    color = PureWhite,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, CharcoalBorder, RectangleShape)
                    .clickable(onClick = onOpenFollowing)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SIGUIENDO (${publicProfile?.followingCount ?: 0})",
                    color = PureWhite,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        if (profile != null) {
            SocialLinksBar(links = profile.socialLinks)
            if (badges.isNotEmpty()) {
                ArcadeBadgesStrip(badges = badges)
            }
            OutlinedButton(
                onClick = onEditUsername,
                shape = RectangleShape,
                border = BorderStroke(1.dp, SteelBorder),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "EDITAR PERFIL",
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        HorizontalDivider(color = CharcoalBorder)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileKpiCell(
                label = "TIEMPO",
                value = formatPlayDuration(profile?.totalPlaySeconds ?: 0L),
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(
                thickness = 1.dp,
                color = CharcoalBorder,
                modifier = Modifier.height(30.dp)
            )
            ProfileKpiCell(
                label = "PARTIDAS",
                value = "${profile?.totalPlays ?: 0}",
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(
                thickness = 1.dp,
                color = CharcoalBorder,
                modifier = Modifier.height(30.dp)
            )
            ProfileKpiCell(
                label = "RÉCORDS",
                value = "$recordsCount",
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(
                thickness = 1.dp,
                color = CharcoalBorder,
                modifier = Modifier.height(30.dp)
            )
            ProfileKpiCell(
                label = "JUEGOS",
                value = "$gamesPlayedCount",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SocialLinksBar(
    links: SocialLinksDto,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val items = remember(links) {
        listOfNotNull(
            links.x?.takeIf { it.isNotBlank() }?.let { Triple("x", "X", it) },
            links.instagram?.takeIf { it.isNotBlank() }?.let { Triple("instagram", "IG", it) },
            links.youtube?.takeIf { it.isNotBlank() }?.let { Triple("youtube", "YT", it) },
            links.twitch?.takeIf { it.isNotBlank() }?.let { Triple("twitch", "TWITCH", it) },
            links.discord?.takeIf { it.isNotBlank() }?.let { Triple("discord", "DISCORD", it) },
            links.github?.takeIf { it.isNotBlank() }?.let { Triple("github", "GITHUB", it) },
            links.website?.takeIf { it.isNotBlank() }?.let { Triple("website", "WEB", it) }
        )
    }
    if (items.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (key, label, value) ->
            Box(
                modifier = Modifier
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, SteelBorder, RectangleShape)
                    .clickable { SocialValidation.openSocialLinkOrCopy(context, key, value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    color = PureWhite,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArcadeBadgesStrip(
    badges: List<ArcadeBadge>,
    modifier: Modifier = Modifier
) {
    if (badges.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        badges.forEach { badge ->
            Column(
                modifier = Modifier
                    .background(GraphiteSurface, RectangleShape)
                    .border(1.dp, CharcoalBorder, RectangleShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (badge.tier != null) "${badge.title} · ${badge.tier}" else badge.title,
                    color = PureWhite,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
                Text(
                    text = badge.subtitle,
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun ProfileKpiCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            color = PureWhite,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1
        )
        Text(
            text = label,
            color = MutedGray,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
    }
}

@Composable
fun PendingScoresBanner(
    pendingCount: Int,
    syncing: Boolean,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GraphiteSurface, RectangleShape)
            .border(1.dp, SteelBorder, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "$pendingCount ${if (pendingCount == 1) "récord pendiente" else "récords pendientes"}",
                    color = PureWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Guardado localmente sin conexión",
                    color = MutedGray,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(
            onClick = onSyncNow,
            enabled = !syncing,
            shape = RectangleShape,
            border = BorderStroke(1.dp, PureWhite),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (syncing) {
                CircularProgressIndicator(
                    color = PureWhite,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Text(
                    text = "SINCRONIZAR",
                    color = PureWhite,
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ProfileRecordRow(
    record: MyHighScoreDto,
    game: GameDto?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val isFirst = record.rank == 1L
    val isPodium = record.rank in 1L..3L

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CarbonSurface, RectangleShape)
            .border(1.dp, if (isFirst) SteelBorder else CharcoalBorder, RectangleShape)
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GameCoverImage(
            slug = game?.slug ?: "",
            contentDescription = game?.title ?: record.gameId,
            contentScale = ContentScale.Crop,
            shape = RectangleShape,
            backgroundColor = ObsidianBlack,
            modifier = Modifier.size(width = 40.dp, height = 52.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = game?.title ?: record.gameId,
                color = PureWhite,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (game != null) {
                    ArcadeBadge(
                        text = game.system.uppercase(),
                        style = ArcadeBadgeStyle.Outline
                    )
                }
                val totalLabel = if (record.totalPlayers > 0) " / ${record.totalPlayers}" else ""
                Box(
                    modifier = Modifier
                        .background(
                            if (isFirst) PureWhite else if (isPodium) GraphiteSurface else ObsidianBlack,
                            RectangleShape
                        )
                        .border(
                            1.dp,
                            if (isPodium) SteelBorder else CharcoalBorder,
                            RectangleShape
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (isPodium) {
                            Icon(
                                imageVector = Icons.Default.EmojiEvents,
                                contentDescription = null,
                                tint = if (isFirst) PureBlack else SilverGray,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                        Text(
                            text = "#${record.rank}$totalLabel",
                            color = if (isFirst) PureBlack else SilverGray,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = numberFmt.format(record.score),
                color = PureWhite,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (onClick != null) {
                Text(
                    text = "VER TOP →",
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
fun ProfileActivityRow(
    stat: UserGameStatDto,
    game: GameDto?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CarbonSurface, RectangleShape)
            .border(1.dp, CharcoalBorder, RectangleShape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GameCoverImage(
            slug = game?.slug ?: "",
            contentDescription = game?.title ?: stat.gameId,
            contentScale = ContentScale.Crop,
            shape = RectangleShape,
            backgroundColor = ObsidianBlack,
            modifier = Modifier.size(width = 40.dp, height = 52.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = game?.title ?: stat.gameId,
                color = PureWhite,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (game != null) {
                    ArcadeBadge(
                        text = game.system.uppercase(),
                        style = ArcadeBadgeStyle.Outline
                    )
                }
                Text(
                    text = "${stat.playCount} ${if (stat.playCount == 1) "partida" else "partidas"}",
                    color = MutedGray,
                    fontSize = 11.sp
                )
            }
        }

        Text(
            text = formatPlayDuration(stat.playSeconds),
            color = SilverGray,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
fun EditUsernameDialog(
    currentUsername: String,
    saving: Boolean,
    errorMsg: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(currentUsername) { mutableStateOf(currentUsername) }
    val trimmed = draft.trim()
    val localValid = SocialValidation.validateUsername(trimmed) == null

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = CarbonSurface,
        shape = RectangleShape,
        title = {
            Text(
                text = "EDITAR NOMBRE",
                color = PureWhite,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Visible en salas Netplay y tablas de clasificación (3–20 caracteres).",
                    color = SilverGray,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 20) draft = it },
                    singleLine = true,
                    enabled = !saving,
                    label = { Text("Nombre de jugador", color = MutedGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = PureWhite,
                        unfocusedBorderColor = SteelBorder,
                        cursorColor = PureWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!errorMsg.isNullOrBlank()) {
                    Text(
                        text = errorMsg,
                        color = DangerRed,
                        fontSize = 11.sp
                    )
                } else if (draft.isNotEmpty() && !localValid) {
                    Text(
                        text = "Usa 3–20 caracteres (solo letras, números o '_').",
                        color = MutedGray,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = !saving && localValid && trimmed != currentUsername,
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PureWhite,
                    contentColor = PureBlack
                )
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = PureBlack,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Text("GUARDAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text("CANCELAR", color = SilverGray, fontSize = 12.sp)
            }
        }
    )
}

@Composable
fun ConfirmClearCacheDialog(
    cacheMb: Double,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        shape = RectangleShape,
        title = {
            Text(
                text = "LIMPIAR CACHÉ LOCAL",
                color = PureWhite,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = "Se eliminarán ROMs, BIOS y Save States locales (${"%.1f".format(cacheMb)} MB). Tus récords y estadísticas en la nube permanecerán intactos.",
                color = SilverGray,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerRed,
                    contentColor = PureWhite
                )
            ) {
                Text("VACIAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR", color = SilverGray, fontSize = 12.sp)
            }
        }
    )
}

@Composable
fun ConfirmSignOutDialog(
    isAnonymous: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        shape = RectangleShape,
        title = {
            Text(
                text = "CERRAR SESIÓN",
                color = PureWhite,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = if (isAnonymous) {
                    "Estás usando una cuenta de INVITADO. Al cerrar sesión perderás el acceso a tus récords y estadísticas actuales."
                } else {
                    "¿Deseas cerrar tu sesión actual en bald.dog ARCADE?"
                },
                color = SilverGray,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerRed,
                    contentColor = PureWhite
                )
            ) {
                Text("SALIR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR", color = SilverGray, fontSize = 12.sp)
            }
        }
    )
}

fun formatPlayDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0m"
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Composable
fun ProfileReplayRow(
    record: MyHighScoreDto,
    game: GameDto?,
    loading: Boolean,
    onWatchReplay: (() -> Unit)?,
    launchState: dog.bald.arcade.ui.screens.ReplayLaunchState? = null,
    modifier: Modifier = Modifier
) {
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val sizeKb = record.replaySizeBytes?.let { (it / 1024.0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CarbonSurface, RectangleShape)
            .border(1.dp, CharcoalBorder, RectangleShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        GameCoverImage(
            slug = game?.slug ?: "",
            contentDescription = game?.title ?: record.gameId,
            contentScale = ContentScale.Crop,
            shape = RectangleShape,
            backgroundColor = ObsidianBlack,
            modifier = Modifier.size(width = 40.dp, height = 52.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = game?.title ?: record.gameId,
                color = PureWhite,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${numberFmt.format(record.score)} pts",
                    color = SilverGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Text(
                    text = "#${record.rank}",
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp
                )
                if (sizeKb != null && sizeKb > 0) {
                    Text(
                        text = "• ${"%.1f".format(sizeKb)} KB",
                        color = MutedGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { onWatchReplay?.invoke() },
            enabled = !loading && onWatchReplay != null,
            shape = RectangleShape,
            border = BorderStroke(1.dp, PureWhite),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = PureWhite,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "VER",
                    color = PureWhite,
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
        if (launchState != null) {
            val pctText = if (launchState.progress >= 0f) " ${(launchState.progress * 100).toInt()}%" else ""
            Text(
                text = "${launchState.stage}$pctText",
                color = SilverGray,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
            )
            if (launchState.progress >= 0f) {
                LinearProgressIndicator(
                    progress = { launchState.progress.coerceIn(0f, 1f) },
                    color = PureWhite,
                    trackColor = GraphiteSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            } else {
                LinearProgressIndicator(
                    color = PureWhite,
                    trackColor = GraphiteSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }
        }
    }
}

@Composable
fun ProfileMatchHistoryRow(
    match: MatchHistoryDto,
    game: GameDto?,
    myUserId: String?,
    modifier: Modifier = Modifier
) {
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val modeLabel = when (match.mode.lowercase()) {
        "lan" -> "LAN"
        "online" -> "ONLINE"
        else -> "SOLO"
    }
    val companions = remember(match.playersJson, myUserId) {
        match.playersJson
            .filter { it.userId != myUserId }
            .map { it.username }
    }
    val dateLabel = remember(match.endedAt, match.startedAt) {
        formatMatchDate(match.endedAt ?: match.startedAt)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CarbonSurface, RectangleShape)
            .border(1.dp, CharcoalBorder, RectangleShape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GameCoverImage(
            slug = game?.slug ?: "",
            contentDescription = game?.title ?: match.gameId,
            contentScale = ContentScale.Crop,
            shape = RectangleShape,
            backgroundColor = ObsidianBlack,
            modifier = Modifier.size(width = 40.dp, height = 52.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = game?.title ?: match.gameId,
                color = PureWhite,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ArcadeBadge(
                    text = modeLabel,
                    style = if (match.mode == "local") ArcadeBadgeStyle.Outline else ArcadeBadgeStyle.Solid
                )
                if (companions.isNotEmpty()) {
                    Text(
                        text = "con ${companions.joinToString(", ")}",
                        color = SilverGray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else if (match.score != null && match.score > 0L) {
                    Text(
                        text = "${numberFmt.format(match.score)} pts",
                        color = SilverGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = formatPlayDuration(match.durationSeconds.toLong()),
                color = PureWhite,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
            if (dateLabel.isNotBlank()) {
                Text(
                    text = dateLabel,
                    color = MutedGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp
                )
            }
        }
    }
}

private fun formatMatchDate(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        val zoned = instant.atZone(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").format(zoned)
    }.getOrElse { "" }
}
