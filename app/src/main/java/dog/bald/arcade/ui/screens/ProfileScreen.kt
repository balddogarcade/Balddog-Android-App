package dog.bald.arcade.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.MatchHistoryDto
import dog.bald.arcade.data.MyHighScoreDto
import dog.bald.arcade.data.ProfileDto
import dog.bald.arcade.data.UserGameStatDto
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.ui.components.ConfirmClearCacheDialog
import dog.bald.arcade.ui.components.ConfirmSignOutDialog
import dog.bald.arcade.ui.components.EditProfileDialog
import dog.bald.arcade.ui.components.PendingScoresBanner
import dog.bald.arcade.ui.components.ProfileActivityRow
import dog.bald.arcade.ui.components.ProfileHeroCard
import dog.bald.arcade.ui.components.ProfileMatchHistoryRow
import dog.bald.arcade.ui.components.ProfileRecordRow
import dog.bald.arcade.ui.components.ProfileReplayRow
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.DangerRed
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.ReadyGreenAccent
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.launch
import uniffi.balddog_android.EngineStatus

private enum class ProfileActivitySection(val label: String) {
    RECORDS("RÉCORDS"),
    REPLAYS("REPLAYS"),
    HISTORY("HISTORIAL"),
    ACTIVITY("TIEMPO")
}

@Composable
fun ProfileScreen(
    profile: ProfileDto?,
    userEmail: String?,
    myUserId: String?,
    avatarSeed: String,
    games: List<GameDto>,
    myHighScores: List<MyHighScoreDto>,
    myMatchHistory: List<MatchHistoryDto>,
    myGameStats: List<UserGameStatDto>,
    pendingScoreCount: Int,
    engineStatus: EngineStatus?,
    cacheBytes: Long,
    onUpdateUsername: suspend (String) -> Unit,
    onFlushPendingScores: suspend () -> Unit,
    onOpenLeaderboard: (GameDto) -> Unit,
    replayLaunchState: ReplayLaunchState? = null,
    onWatchReplay: suspend (MyHighScoreDto, GameDto) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit,
    publicProfile: dog.bald.arcade.data.PublicProfileDto? = null,
    onOpenFollowers: () -> Unit = {},
    onOpenFollowing: () -> Unit = {},
    onUpdateProfile: (suspend (String, String?, String?, String, Boolean, dog.bald.arcade.data.SocialLinksDto) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val cacheMb = cacheBytes / (1024.0 * 1024.0)
    val gamesById = remember(games) { games.associateBy { it.id } }

    var selectedSection by remember { mutableIntStateOf(ProfileActivitySection.RECORDS.ordinal) }
    var showAllItems by remember { mutableStateOf(false) }

    var showEditNameDialog by remember { mutableStateOf(false) }
    var savingUsername by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf<String?>(null) }

    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var syncingScores by remember { mutableStateOf(false) }
    var loadingReplayGameId by remember { mutableStateOf<String?>(null) }

    val myReplays = remember(myHighScores) {
        myHighScores.filter { !it.replayPath.isNullOrBlank() }
    }
    val displayedRecords = remember(myHighScores, showAllItems) {
        if (showAllItems) myHighScores else myHighScores.take(5)
    }
    val displayedReplays = remember(myReplays, showAllItems) {
        if (showAllItems) myReplays else myReplays.take(5)
    }
    val displayedHistory = remember(myMatchHistory, showAllItems) {
        if (showAllItems) myMatchHistory else myMatchHistory.take(5)
    }
    val displayedStats = remember(myGameStats, showAllItems) {
        if (showAllItems) myGameStats else myGameStats.take(5)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "hero") {
            ProfileHeroCard(
                profile = profile,
                userEmail = userEmail,
                avatarSeed = avatarSeed,
                recordsCount = myHighScores.size,
                gamesPlayedCount = myGameStats.size,
                onEditUsername = {
                    usernameError = null
                    showEditNameDialog = true
                },
                publicProfile = publicProfile,
                onOpenFollowers = onOpenFollowers,
                onOpenFollowing = onOpenFollowing
            )
        }

        if (pendingScoreCount > 0) {
            item(key = "pending_scores") {
                PendingScoresBanner(
                    pendingCount = pendingScoreCount,
                    syncing = syncingScores,
                    onSyncNow = {
                        if (!syncingScores) {
                            scope.launch {
                                syncingScores = true
                                runCatching { onFlushPendingScores() }
                                syncingScores = false
                            }
                        }
                    }
                )
            }
        }

        item(key = "section_toggle") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianBlack, RectangleShape)
                    .border(1.dp, CharcoalBorder, RectangleShape)
            ) {
                ProfileActivitySection.entries.forEachIndexed { idx, section ->
                    val active = selectedSection == idx
                    val count = when (section) {
                        ProfileActivitySection.RECORDS -> myHighScores.size
                        ProfileActivitySection.REPLAYS -> myReplays.size
                        ProfileActivitySection.HISTORY -> myMatchHistory.size
                        ProfileActivitySection.ACTIVITY -> myGameStats.size
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (active) PureWhite else CarbonSurface)
                            .clickable {
                                selectedSection = idx
                                showAllItems = false
                            }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${section.label} ($count)",
                            color = if (active) PureBlack else SilverGray,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            letterSpacing = 0.2.sp
                        )
                    }
                }
            }
        }

        when (ProfileActivitySection.entries[selectedSection]) {
            ProfileActivitySection.RECORDS -> {
                if (myHighScores.isEmpty()) {
                    item(key = "empty_records") {
                        ProfileEmptyCard(
                            icon = Icons.Default.EmojiEvents,
                            title = "Sin récords registrados",
                            subtitle = "Juega en solitario con 1 crédito y sin Save States para entrar al ranking global."
                        )
                    }
                } else {
                    items(displayedRecords, key = { "rec_${it.gameId}" }) { record ->
                        val game = gamesById[record.gameId]
                        ProfileRecordRow(
                            record = record,
                            game = game,
                            onClick = game?.let { g -> { onOpenLeaderboard(g) } }
                        )
                    }
                    if (myHighScores.size > 5) {
                        item(key = "toggle_records") {
                            ExpandListButton(
                                expanded = showAllItems,
                                totalCount = myHighScores.size,
                                onClick = { showAllItems = !showAllItems }
                            )
                        }
                    }
                }
            }

            ProfileActivitySection.REPLAYS -> {
                if (myReplays.isEmpty()) {
                    item(key = "empty_replays") {
                        ProfileEmptyCard(
                            icon = Icons.Default.PlayCircleOutline,
                            title = "Sin replays guardados",
                            subtitle = "Tus récords en solitario (1 crédito) guardarán automáticamente su replay aquí."
                        )
                    }
                } else {
                    items(displayedReplays, key = { "rep_${it.gameId}" }) { record ->
                        val game = gamesById[record.gameId]
                        val itemLaunchState = replayLaunchState?.takeIf { it.replayPath == record.replayPath }
                        ProfileReplayRow(
                            record = record,
                            game = game,
                            loading = itemLaunchState != null,
                            launchState = itemLaunchState,
                            onWatchReplay = game?.let { g ->
                                {
                                    if (replayLaunchState == null) {
                                        scope.launch {
                                            runCatching { onWatchReplay(record, g) }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    if (myReplays.size > 5) {
                        item(key = "toggle_replays") {
                            ExpandListButton(
                                expanded = showAllItems,
                                totalCount = myReplays.size,
                                onClick = { showAllItems = !showAllItems }
                            )
                        }
                    }
                }
            }

            ProfileActivitySection.HISTORY -> {
                if (myMatchHistory.isEmpty()) {
                    item(key = "empty_history") {
                        ProfileEmptyCard(
                            icon = Icons.Default.History,
                            title = "Sin historial de partidas",
                            subtitle = "Tus partidas en solitario, LAN e Internet (salas públicas) aparecerán aquí."
                        )
                    }
                } else {
                    items(displayedHistory, key = { "hist_${it.id}" }) { match ->
                        ProfileMatchHistoryRow(
                            match = match,
                            game = gamesById[match.gameId],
                            myUserId = myUserId
                        )
                    }
                    if (myMatchHistory.size > 5) {
                        item(key = "toggle_history") {
                            ExpandListButton(
                                expanded = showAllItems,
                                totalCount = myMatchHistory.size,
                                onClick = { showAllItems = !showAllItems }
                            )
                        }
                    }
                }
            }

            ProfileActivitySection.ACTIVITY -> {
                if (myGameStats.isEmpty()) {
                    item(key = "empty_activity") {
                        ProfileEmptyCard(
                            icon = Icons.Default.Schedule,
                            title = "Sin actividad reciente",
                            subtitle = "Tu tiempo jugado y número de partidas por juego aparecerán aquí."
                        )
                    }
                } else {
                    items(displayedStats, key = { "stat_${it.gameId}" }) { stat ->
                        ProfileActivityRow(
                            stat = stat,
                            game = gamesById[stat.gameId]
                        )
                    }
                    if (myGameStats.size > 5) {
                        item(key = "toggle_stats") {
                            ExpandListButton(
                                expanded = showAllItems,
                                totalCount = myGameStats.size,
                                onClick = { showAllItems = !showAllItems }
                            )
                        }
                    }
                }
            }
        }

        item(key = "system_section") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CarbonSurface, RectangleShape)
                    .border(1.dp, CharcoalBorder, RectangleShape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SISTEMA Y CUENTA",
                    color = MutedGray,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFamily,
                    letterSpacing = 0.5.sp
                )

                // Almacenamiento local
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
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = SilverGray,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Caché local",
                                color = PureWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${"%.1f".format(cacheMb)} MB (ROMs, BIOS y States)",
                                color = MutedGray,
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMonoFamily
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showClearCacheDialog = true },
                        enabled = cacheBytes > 0L,
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, SteelBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = SilverGray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LIMPIAR",
                            color = SilverGray,
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFamily
                        )
                    }
                }

                HorizontalDivider(color = CharcoalBorder)

                // Motor y Diagnóstico bajo demanda
                val fbneoReady = engineStatus?.fbneoReady == true
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
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = SilverGray,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Motor Rust v${engineStatus?.engineVersion ?: "?"}",
                                    color = PureWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (fbneoReady) ReadyGreenAccent else DangerRed)
                                )
                            }
                            Text(
                                text = "${engineStatus?.targetArch ?: "?"} · FBNeo ${if (fbneoReady) "LISTO" else "NO DETECTADO"}",
                                color = MutedGray,
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMonoFamily
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, SteelBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "DIAGNÓSTICO",
                            color = PureWhite,
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFamily
                        )
                    }
                }

                HorizontalDivider(color = CharcoalBorder)

                OutlinedButton(
                    onClick = { showSignOutDialog = true },
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, CharcoalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = SilverGray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CERRAR SESIÓN",
                        color = SilverGray,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showEditNameDialog && profile != null) {
        EditProfileDialog(
            profile = profile,
            fallbackAvatarSeed = avatarSeed,
            saving = savingUsername,
            errorMsg = usernameError,
            onDismiss = { showEditNameDialog = false },
            onConfirm = { newName, bio, countryCode, newSeed, isPrivate, socialLinks ->
                scope.launch {
                    savingUsername = true
                    usernameError = null
                    runCatching {
                        if (onUpdateProfile != null) {
                            onUpdateProfile(newName, bio, countryCode, newSeed, isPrivate, socialLinks)
                        } else {
                            onUpdateUsername(newName)
                        }
                    }
                        .onSuccess {
                            savingUsername = false
                            showEditNameDialog = false
                        }
                        .onFailure { err ->
                            savingUsername = false
                            val msg = err.toUserMessage("No se pudo actualizar el perfil")
                            usernameError = if (msg.contains("unique", ignoreCase = true) ||
                                msg.contains("duplicate", ignoreCase = true) ||
                                msg.contains("en uso", ignoreCase = true)
                            ) {
                                "Ese nombre ya está en uso por otro jugador."
                            } else {
                                msg
                            }
                        }
                }
            }
        )
    }

    if (showClearCacheDialog) {
        ConfirmClearCacheDialog(
            cacheMb = cacheMb,
            onDismiss = { showClearCacheDialog = false },
            onConfirm = {
                showClearCacheDialog = false
                onClearCache()
            }
        )
    }

    if (showSignOutDialog) {
        ConfirmSignOutDialog(
            isAnonymous = profile?.isAnonymous == true,
            onDismiss = { showSignOutDialog = false },
            onConfirm = {
                showSignOutDialog = false
                onSignOut()
            }
        )
    }
}

@Composable
private fun ProfileEmptyCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CarbonSurface, RectangleShape)
            .border(1.dp, CharcoalBorder, RectangleShape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MutedGray,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = title,
            color = PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            color = MutedGray,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ExpandListButton(
    expanded: Boolean,
    totalCount: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(GraphiteSurface, RectangleShape)
            .border(1.dp, CharcoalBorder, RectangleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (expanded) "MOSTRAR MENOS" else "VER TODOS ($totalCount)",
            color = SilverGray,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
