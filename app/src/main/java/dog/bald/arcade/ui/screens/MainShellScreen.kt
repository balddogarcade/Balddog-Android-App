package dog.bald.arcade.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.async
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dog.bald.arcade.R
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.ExitSyncCoordinator
import dog.bald.arcade.data.GameBiosDto
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.GameRankingSummaryDto
import dog.bald.arcade.data.IpfsRomManager
import dog.bald.arcade.data.LeaderboardEntryDto
import dog.bald.arcade.data.MatchHistoryDto
import dog.bald.arcade.data.MyHighScoreDto
import dog.bald.arcade.data.PendingMatchHistoryQueue
import dog.bald.arcade.data.PendingScoreQueue
import dog.bald.arcade.data.ProfileCache
import dog.bald.arcade.data.PublicProfileDto
import dog.bald.arcade.data.RoomDto
import dog.bald.arcade.data.UserGameStatDto
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.net.LanNsdManager
import dog.bald.arcade.ui.components.AppPreloadScreen
import dog.bald.arcade.ui.components.ArcadeAvatarOrPlaceholder
import dog.bald.arcade.ui.components.ArcadeGameCard
import dog.bald.arcade.ui.components.ArcadeSearchBar
import dog.bald.arcade.ui.components.DiagnosticsSheet
import dog.bald.arcade.ui.components.GameCoverImage
import dog.bald.arcade.ui.components.GameLeaderboardSheet
import dog.bald.arcade.ui.components.NewRecordDialog
import dog.bald.arcade.ui.components.PostMatchRecordBannerData
import dog.bald.arcade.ui.components.detectImprovedHighScore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.balddog_android.EngineStatus
import uniffi.balddog_android.discoverPublicUdpEndpoint
import uniffi.balddog_android.getRecentLogs
import uniffi.balddog_android.logFromAndroid
import uniffi.balddog_android.pingEngine

private enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    CATALOG("Catálogo", Icons.Default.SportsEsports),
    RANKINGS("Rankings", Icons.Default.EmojiEvents),
    ROOMS("Salas", Icons.Default.Groups),
    PROFILE("Perfil", Icons.Default.Person),
}

private sealed interface AppOverlay {
    data class PublicProfile(
        val userId: String,
    ) : AppOverlay

    data class FollowList(
        val userId: String,
        val direction: String,
        val username: String,
    ) : AppOverlay

    data class GameRanking(
        val game: GameDto,
    ) : AppOverlay

    data class GameDetail(
        val game: GameDto,
    ) : AppOverlay

    data object WikiHelp : AppOverlay
}

data class ReplayLaunchState(
    val replayPath: String,
    val stage: String,
    val progress: Float,
)

@Composable
fun MainShellScreen(
    repo: ArcadeRepository,
    romManager: IpfsRomManager,
    lanNsd: LanNsdManager,
    nativeLibDir: String,
    currentUserId: String = repo.currentUserId().orEmpty(),
    gameReturnCounter: Int = 0,
    onActiveHostRoomChanged: (String?) -> Unit = {},
    onSignedOut: () -> Unit,
    onLaunchLocalRom: (GameDto) -> Unit,
    onLaunchNetplayMatch: (GameDto, RoomDto, Int, Int, Int) -> Unit,
    onLaunchReplay: (GameDto, java.io.File, dog.bald.arcade.data.LeaderboardEntryDto) -> Unit = { _, _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pagerState =
        rememberPagerState(
            initialPage = MainTab.CATALOG.ordinal,
            pageCount = { MainTab.entries.size },
        )
    var isInitialLoading by remember { mutableStateOf(true) }
    var games by remember { mutableStateOf<List<GameDto>>(emptyList()) }
    var biosList by remember { mutableStateOf<List<GameBiosDto>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<RoomDto>>(emptyList()) }
    var profile by remember(currentUserId) {
        mutableStateOf(ProfileCache.load(context, currentUserId))
    }
    var hasLoadedInitialScores by remember(currentUserId) { mutableStateOf(false) }
    var myHighScores by remember { mutableStateOf<List<MyHighScoreDto>>(emptyList()) }
    var myMatchHistory by remember { mutableStateOf<List<MatchHistoryDto>>(emptyList()) }
    var myGameStats by remember { mutableStateOf<List<UserGameStatDto>>(emptyList()) }
    var myPublicProfile by remember { mutableStateOf<PublicProfileDto?>(null) }
    var rankingSummaries by remember { mutableStateOf<Map<String, GameRankingSummaryDto>>(emptyMap()) }
    var myFollowingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var overlayStack by remember { mutableStateOf<List<AppOverlay>>(emptyList()) }
    val openGameDetail: (GameDto) -> Unit = { game ->
        val top = overlayStack.lastOrNull()
        if (top !is AppOverlay.GameDetail || top.game.id != game.id) {
            overlayStack = overlayStack + AppOverlay.GameDetail(game)
        }
    }
    var replayLaunchState by remember { mutableStateOf<ReplayLaunchState?>(null) }
    var postMatchRecordBanner by remember { mutableStateOf<PostMatchRecordBannerData?>(null) }
    var pendingScoreCount by remember { mutableIntStateOf(PendingScoreQueue.pendingCount(context, repo.currentUserId())) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var userMenuExpanded by remember { mutableStateOf(false) }
    val userEmail = remember(profile) { repo.currentUserEmail() }
    val avatarSeed: String? =
        remember(profile) {
            profile?.avatarSeed?.takeIf { it.isNotBlank() }
        }
    var engineStatus by remember { mutableStateOf<EngineStatus?>(null) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var syncError by remember { mutableStateOf<String?>(null) }

    var activeRoom by remember { mutableStateOf<RoomDto?>(null) }
    var roomNoticeMsg by remember { mutableStateOf<String?>(null) }
    var leaderboardGame by remember { mutableStateOf<GameDto?>(null) }
    var myUdpPort by remember { mutableIntStateOf(7000) }
    var myEndpoint by remember { mutableStateOf("") }
    var myVoiceUdpPort by remember { mutableIntStateOf(8000) }
    var myVoiceEndpoint by remember { mutableStateOf("") }

    suspend fun resolveUdpEndpoint(
        mode: String,
        port: Int,
        localIp: String?,
    ): String =
        if (mode == "lan") {
            "${localIp ?: "127.0.0.1"}:$port"
        } else {
            val publicEp =
                withContext(Dispatchers.IO) {
                    runCatching { discoverPublicUdpEndpoint(port.toUShort()) }
                        .onFailure { t ->
                            logFromAndroid("WARN", "STUN no respondió en puerto $port: ${t.message}. Usando fallback local.")
                        }.getOrNull()
                }
            if (publicEp != null) {
                dog.bald.arcade.net.NetplayEndpointResolver
                    .formatCandidateEndpoint(publicEp, localIp, port)
            } else {
                "${localIp ?: "127.0.0.1"}:$port"
            }
        }

    var downloadingGameId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedFiles by remember { mutableStateOf(romManager.listDownloadedFilenames()) }
    var cacheBytes by remember { mutableLongStateOf(romManager.totalCacheBytes()) }
    var gamePendingDelete by remember { mutableStateOf<GameDto?>(null) }

    fun refreshLocalState() {
        downloadedFiles = romManager.listDownloadedFilenames()
        cacheBytes = romManager.totalCacheBytes()
    }

    fun performSignOut() {
        scope.launch {
            withContext(Dispatchers.IO) {
                PendingScoreQueue.clear(context)
                PendingMatchHistoryQueue.clear(context)
            }
            runCatching { repo.signOut() }
            onSignedOut()
        }
    }

    fun refreshAll() {
        scope.launch {
            syncError = null
            try {
                runCatching {
                    engineStatus = pingEngine(nativeLibDir)
                    logs = getRecentLogs()
                }.onFailure { t ->
                    syncError = t.toUserMessage("Error conectando con motor nativo")
                }
                try {
                    runCatching { PendingMatchHistoryQueue.flushAll(context, repo) }
                    kotlinx.coroutines.coroutineScope {
                        val gamesDef = async { repo.fetchGames() }
                        val biosDef = async { repo.fetchBios() }
                        val roomsDef = async { repo.fetchOpenRooms() }
                        val profileDef = async { runCatching { repo.fetchMyProfile() }.getOrNull() }
                        val scoresDef = async { runCatching { repo.fetchMyHighScores() } }
                        val historyDef = async { runCatching { repo.fetchMyMatchHistory() }.getOrDefault(emptyList()) }
                        val statsDef = async { runCatching { repo.fetchMyGameStats() }.getOrDefault(emptyList()) }
                        val publicDef =
                            async {
                                repo.currentUserId()?.let { uid -> runCatching { repo.fetchPublicProfile(uid) }.getOrNull() }
                            }
                        val summariesDef =
                            async {
                                runCatching { repo.fetchGamesRankingSummary().associateBy { it.gameId } }.getOrDefault(emptyMap())
                            }
                        val followingDef = async { runCatching { repo.fetchMyFollowingIds() }.getOrDefault(emptySet()) }

                        games = gamesDef.await()
                        biosList = biosDef.await()
                        rooms = roomsDef.await()
                        profileDef.await()?.let { profile = it }
                        scoresDef.await().onSuccess { fetchedScores ->
                            myHighScores = fetchedScores
                            hasLoadedInitialScores = true
                        }
                        myMatchHistory = historyDef.await()
                        myGameStats = statsDef.await()
                        myPublicProfile = publicDef.await()
                        rankingSummaries = summariesDef.await()
                        myFollowingIds = followingDef.await()
                    }
                    pendingScoreCount = PendingScoreQueue.pendingCount(context, repo.currentUserId())
                    refreshLocalState()
                } catch (t: Throwable) {
                    val msg = t.toUserMessage("Error sincronizando catálogo")
                    syncError = if (syncError == null) msg else "$syncError\n$msg"
                }
            } finally {
                isInitialLoading = false
            }
        }
    }

    fun downloadOnly(
        game: GameDto,
        launchAfter: Boolean = false,
    ) {
        if (downloadingGameId != null) return
        scope.launch {
            downloadingGameId = game.id
            downloadProgress = 0f
            syncError = null
            try {
                val wasDownloaded = romManager.isGameDownloaded(game)
                romManager.ensureGameReady(
                    game = game,
                    allGames = games,
                    allBios = biosList,
                ) { _, progress ->
                    downloadProgress = progress
                }
                if (!wasDownloaded) {
                    runCatching { repo.incrementGameDownload(game.id) }
                    games =
                        games.map {
                            if (it.id == game.id) it.copy(downloadCount = it.downloadCount + 1) else it
                        }
                }
                refreshLocalState()
                if (launchAfter) {
                    onLaunchLocalRom(game)
                }
            } catch (t: Throwable) {
                syncError = t.toUserMessage("Error descargando ROM")
            } finally {
                downloadingGameId = null
            }
        }
    }

    LaunchedEffect(currentUserId) {
        refreshAll()
    }

    // Sincronizar sesión y datos al volver a primer plano (sin disparar modal de nuevo récord)
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasResumedOnce by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (!hasResumedOnce) {
                        hasResumedOnce = true
                    } else {
                        scope.launch {
                            repo.refreshSessionIfNeeded()
                            runCatching { PendingScoreQueue.flushAll(context, repo) }
                            runCatching { PendingMatchHistoryQueue.flushAll(context, repo) }
                            pendingScoreCount = PendingScoreQueue.pendingCount(context, repo.currentUserId())
                            runCatching { games = repo.fetchGames() }
                            runCatching { repo.fetchMyProfile() }.getOrNull()?.let { fetchedProfile ->
                                profile = fetchedProfile
                            }
                            runCatching { repo.fetchMyHighScores() }.onSuccess { fetchedScores ->
                                myHighScores = fetchedScores
                                hasLoadedInitialScores = true
                            }
                            runCatching { myMatchHistory = repo.fetchMyMatchHistory() }
                            runCatching { myGameStats = repo.fetchMyGameStats() }
                            repo.currentUserId()?.let { uid ->
                                runCatching { myPublicProfile = repo.fetchPublicProfile(uid) }
                            }
                            runCatching {
                                rankingSummaries = repo.fetchGamesRankingSummary().associateBy { it.gameId }
                            }
                        }
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Evaluar nuevo récord 1CC únicamente al regresar explícitamente de GameActivity
    LaunchedEffect(gameReturnCounter) {
        if (gameReturnCounter <= 0) return@LaunchedEffect
        val prevScores =
            if (hasLoadedInitialScores) {
                myHighScores.associateBy { it.gameId }
            } else {
                null
            }
        ExitSyncCoordinator.awaitInFlight(timeoutMs = 8_000L)
        runCatching { PendingScoreQueue.flushAll(context, repo) }
        runCatching { PendingMatchHistoryQueue.flushAll(context, repo) }
        pendingScoreCount = PendingScoreQueue.pendingCount(context, repo.currentUserId())
        runCatching { games = repo.fetchGames() }
        runCatching { repo.fetchMyProfile() }.getOrNull()?.let { fetchedProfile ->
            profile = fetchedProfile
        }
        val updatedScores = runCatching { repo.fetchMyHighScores() }.getOrNull()
        if (updatedScores != null) {
            myHighScores = updatedScores
            hasLoadedInitialScores = true
            if (prevScores != null) {
                val improved = detectImprovedHighScore(prevScores, updatedScores)
                if (improved != null) {
                    val gameObj = games.firstOrNull { it.id == improved.gameId }
                    if (gameObj != null) {
                        postMatchRecordBanner =
                            PostMatchRecordBannerData(
                                game = gameObj,
                                score = improved.score,
                                previousRank = prevScores[improved.gameId]?.rank,
                                newRank = improved.rank,
                            )
                    }
                }
            }
        }
        runCatching { myMatchHistory = repo.fetchMyMatchHistory() }
        runCatching { myGameStats = repo.fetchMyGameStats() }
        repo.currentUserId()?.let { uid ->
            runCatching { myPublicProfile = repo.fetchPublicProfile(uid) }
        }
        runCatching {
            rankingSummaries = repo.fetchGamesRankingSummary().associateBy { it.gameId }
        }
    }

    // Auto-refrescar salas cada 3s mientras el usuario está en la pestaña Salas
    LaunchedEffect(pagerState.currentPage, activeRoom) {
        if (MainTab.entries[pagerState.currentPage] == MainTab.ROOMS && activeRoom == null) {
            while (true) {
                runCatching { rooms = repo.fetchOpenRooms() }
                delay(3000)
            }
        } else if (MainTab.entries[pagerState.currentPage] == MainTab.PROFILE) {
            runCatching {
                PendingScoreQueue.flushAll(context, repo)
                PendingMatchHistoryQueue.flushAll(context, repo)
                profile = repo.fetchMyProfile()
                myHighScores = repo.fetchMyHighScores()
                myMatchHistory = repo.fetchMyMatchHistory()
                myGameStats = repo.fetchMyGameStats()
                repo.currentUserId()?.let { uid ->
                    myPublicProfile = repo.fetchPublicProfile(uid)
                }
                pendingScoreCount = PendingScoreQueue.pendingCount(context, repo.currentUserId())
            }
        }
    }

    val currentRoom = activeRoom
    LaunchedEffect(currentRoom?.id, currentRoom?.hostId) {
        val hostRoomId =
            if (currentRoom != null && currentRoom.hostId == repo.currentUserId()) {
                currentRoom.id
            } else {
                null
            }
        onActiveHostRoomChanged(hostRoomId)
    }

    val topOverlay = overlayStack.lastOrNull()
    Box(modifier = Modifier.fillMaxSize()) {
        if (currentRoom != null) {
            val roomGame = games.firstOrNull { it.id == currentRoom.gameId }
            RoomLobbyScreen(
                repo = repo,
                lanNsd = lanNsd,
                initialRoom = currentRoom,
                game = roomGame,
                myUsername = profile?.username ?: "Jugador",
                myEndpoint = myEndpoint,
                myUdpPort = myUdpPort,
                myVoiceEndpoint = myVoiceEndpoint,
                myAvatarSeed = avatarSeed ?: currentUserId,
                hasActiveOverlay = topOverlay != null,
                onOpenUserProfile = { uid ->
                    overlayStack = overlayStack + AppOverlay.PublicProfile(uid)
                },
                onLeave = { reason ->
                    overlayStack = emptyList()
                    activeRoom = null
                    scope.launch { pagerState.animateScrollToPage(MainTab.ROOMS.ordinal) }
                    if (reason != null) {
                        roomNoticeMsg = reason
                    }
                    refreshAll()
                },
                onStartMatch = { startedRoom, mySlot ->
                    if (roomGame == null) {
                        Result.failure(IllegalStateException("Juego no encontrado en el catálogo"))
                    } else {
                        try {
                            val wasDownloaded = romManager.isGameDownloaded(roomGame)
                            romManager.ensureGameReady(roomGame, games, biosList) { _, p ->
                                downloadProgress = p
                            }
                            if (!wasDownloaded) {
                                runCatching { repo.incrementGameDownload(roomGame.id) }
                                games =
                                    games.map {
                                        if (it.id == roomGame.id) it.copy(downloadCount = it.downloadCount + 1) else it
                                    }
                            }
                            overlayStack = emptyList()
                            activeRoom = null
                            onLaunchNetplayMatch(roomGame, startedRoom, mySlot, myUdpPort, myVoiceUdpPort)
                            Result.success(Unit)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Result.failure(t)
                        }
                    }
                },
            )
        } else if (isInitialLoading) {
            AppPreloadScreen(message = "Cargando catálogo...")
        } else {
            Scaffold(
                containerColor = ObsidianBlack,
                bottomBar = {
                    NavigationBar(containerColor = CarbonSurface) {
                        MainTab.entries.forEachIndexed { idx, tab ->
                            NavigationBarItem(
                                selected = pagerState.targetPage == idx,
                                onClick = { scope.launch { pagerState.animateScrollToPage(idx) } },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, fontSize = 11.sp) },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = PureWhite,
                                        selectedTextColor = PureWhite,
                                        unselectedIconColor = MutedGray,
                                        unselectedTextColor = MutedGray,
                                        indicatorColor = GraphiteSurface,
                                    ),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo_text),
                            contentDescription = "Bald Dog",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(34.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .background(CarbonSurface, RectangleShape)
                                        .border(1.dp, SteelBorder, RectangleShape)
                                        .clickable { overlayStack = overlayStack + AppOverlay.WikiHelp }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                        contentDescription = "Wiki / Ayuda",
                                        tint = PureWhite,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "AYUDA",
                                        color = PureWhite,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { userMenuExpanded = true },
                                ) {
                                    ArcadeAvatarOrPlaceholder(
                                        avatarSeed = avatarSeed,
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Menú de usuario",
                                        tint = SilverGray,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = userMenuExpanded,
                                    onDismissRequest = { userMenuExpanded = false },
                                    containerColor = CarbonSurface,
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = profile?.username ?: "Jugador",
                                                    color = PureWhite,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp,
                                                )
                                                if (!userEmail.isNullOrBlank()) {
                                                    Text(
                                                        text = userEmail,
                                                        color = MutedGray,
                                                        fontSize = 11.sp,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {},
                                        enabled = false,
                                    )
                                    HorizontalDivider(color = CharcoalBorder)
                                    DropdownMenuItem(
                                        text = { Text("Perfil", color = PureWhite, fontSize = 13.sp) },
                                        onClick = {
                                            userMenuExpanded = false
                                            scope.launch { pagerState.animateScrollToPage(MainTab.PROFILE.ordinal) }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Diagnóstico", color = SilverGray, fontSize = 13.sp) },
                                        onClick = {
                                            userMenuExpanded = false
                                            engineStatus = runCatching { pingEngine(nativeLibDir) }.getOrNull()
                                            logs = getRecentLogs()
                                            showDiagnosticsSheet = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Cerrar sesión", color = SilverGray, fontSize = 13.sp) },
                                        onClick = {
                                            userMenuExpanded = false
                                            performSignOut()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (syncError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = syncError ?: "", color = SilverGray, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (MainTab.entries[page]) {
                            MainTab.CATALOG ->
                                CatalogTab(
                                    games = games,
                                    downloadedFiles = downloadedFiles,
                                    downloadingGameId = downloadingGameId,
                                    downloadProgress = downloadProgress,
                                    busy = downloadingGameId != null,
                                    rankingSummaries = rankingSummaries,
                                    onRefresh = { refreshAll() },
                                    onDownload = { downloadOnly(it) },
                                    onPlay = { onLaunchLocalRom(it) },
                                    onGameDetail = openGameDetail,
                                    onLeaderboard = { leaderboardGame = it },
                                    onDeleteClick = { gamePendingDelete = it },
                                )

                            MainTab.RANKINGS ->
                                RankingsScreen(
                                    repo = repo,
                                    games = games,
                                    onOpenPublicProfile = { uid ->
                                        overlayStack = overlayStack + AppOverlay.PublicProfile(uid)
                                    },
                                    onOpenGameRanking = { game ->
                                        overlayStack = overlayStack + AppOverlay.GameRanking(game)
                                    },
                                    onOpenGameDetail = openGameDetail,
                                )

                            MainTab.ROOMS ->
                                RoomsTab(
                                    rooms = rooms,
                                    allGames = games,
                                    libraryGames = games.filter { downloadedFiles.contains(it.romFilename) },
                                    lanNsd = lanNsd,
                                    followingIds = myFollowingIds,
                                    roomNoticeMsg = roomNoticeMsg,
                                    onDismissRoomNotice = { roomNoticeMsg = null },
                                    onGoToCatalog = { scope.launch { pagerState.animateScrollToPage(MainTab.CATALOG.ordinal) } },
                                    onRefresh = { refreshAll() },
                                    onLookupRoom = { codeOrId -> repo.fetchRoomByCodeOrId(codeOrId) },
                                    onCreateRoom = { game, mode, isPrivate ->
                                        roomNoticeMsg = null
                                        scope.launch {
                                            syncError = null
                                            try {
                                                val port = (7000..7099).random()
                                                val voicePort = port + 1000
                                                myUdpPort = port
                                                myVoiceUdpPort = voicePort
                                                val localIp = lanNsd.getLocalIpv4Address()
                                                val endpoint = resolveUdpEndpoint(mode, port, localIp)
                                                val voiceEndpoint = resolveUdpEndpoint(mode, voicePort, localIp)
                                                myEndpoint = endpoint
                                                myVoiceEndpoint = voiceEndpoint
                                                val created =
                                                    repo.createRoom(
                                                        gameId = game.id,
                                                        mode = mode,
                                                        maxPlayers = game.maxPlayers,
                                                        username = profile?.username ?: "Host",
                                                        endpoint = endpoint,
                                                        localIp = localIp,
                                                        localPort = port,
                                                        isPrivate = isPrivate,
                                                        voiceEndpoint = voiceEndpoint,
                                                        avatarSeed = avatarSeed,
                                                    )
                                                activeRoom = created
                                            } catch (t: Throwable) {
                                                syncError = t.toUserMessage("Error creando sala")
                                            }
                                        }
                                    },
                                    onJoinRoom = { room ->
                                        roomNoticeMsg = null
                                        scope.launch {
                                            try {
                                                val port = (7100..7199).random()
                                                val voicePort = port + 1000
                                                myUdpPort = port
                                                myVoiceUdpPort = voicePort
                                                val localIp = lanNsd.getLocalIpv4Address()
                                                val endpoint = resolveUdpEndpoint(room.mode, port, localIp)
                                                val voiceEndpoint = resolveUdpEndpoint(room.mode, voicePort, localIp)
                                                myEndpoint = endpoint
                                                myVoiceEndpoint = voiceEndpoint
                                                val joinedRoom =
                                                    repo.updateRoomSlot(
                                                        roomId = room.id,
                                                        action = "join",
                                                        endpoint = endpoint,
                                                        voiceEndpoint = voiceEndpoint.ifBlank { null },
                                                    )
                                                activeRoom = joinedRoom
                                            } catch (ce: CancellationException) {
                                                throw ce
                                            } catch (t: Throwable) {
                                                roomNoticeMsg = t.toUserMessage("No se pudo unir a la sala")
                                            }
                                        }
                                    },
                                )

                            MainTab.PROFILE ->
                                androidx.compose.runtime.CompositionLocalProvider(
                                    dog.bald.arcade.ui.components.LocalOpenGameDetail provides openGameDetail,
                                ) {
                                    ProfileScreen(
                                    profile = profile,
                                    userEmail = userEmail,
                                    myUserId = repo.currentUserId(),
                                    avatarSeed = avatarSeed,
                                    games = games,
                                    myHighScores = myHighScores,
                                    myMatchHistory = myMatchHistory,
                                    myGameStats = myGameStats,
                                    pendingScoreCount = pendingScoreCount,
                                    engineStatus = engineStatus,
                                    cacheBytes = cacheBytes,
                                    onUpdateUsername = { newName ->
                                        profile = repo.updateUsername(newName)
                                    },
                                    publicProfile = myPublicProfile,
                                    onOpenFollowers = {
                                        val uid = repo.currentUserId()
                                        if (uid != null) {
                                            overlayStack = overlayStack + AppOverlay.FollowList(uid, "followers", profile?.username ?: "Tú")
                                        }
                                    },
                                    onOpenFollowing = {
                                        val uid = repo.currentUserId()
                                        if (uid != null) {
                                            overlayStack = overlayStack + AppOverlay.FollowList(uid, "following", profile?.username ?: "Tú")
                                        }
                                    },
                                    onUpdateProfile = { newName, bio, countryCode, newSeed, isPrivate, socialLinks ->
                                        profile = repo.updateMyProfile(newName, bio, countryCode, newSeed, isPrivate, socialLinks)
                                        repo.currentUserId()?.let { uid ->
                                            runCatching { myPublicProfile = repo.fetchPublicProfile(uid) }
                                        }
                                    },
                                    onFlushPendingScores = {
                                        PendingScoreQueue.flushAll(context, repo)
                                        PendingMatchHistoryQueue.flushAll(context, repo)
                                        pendingScoreCount = PendingScoreQueue.pendingCount(context, repo.currentUserId())
                                        myHighScores = runCatching { repo.fetchMyHighScores() }.getOrDefault(myHighScores)
                                        myMatchHistory = runCatching { repo.fetchMyMatchHistory() }.getOrDefault(myMatchHistory)
                                    },
                                    onOpenLeaderboard = { leaderboardGame = it },
                                    replayLaunchState = replayLaunchState,
                                    onWatchReplay = { record, game ->
                                        val rPath = record.replayPath
                                        if (!rPath.isNullOrBlank() && replayLaunchState == null) {
                                            replayLaunchState = ReplayLaunchState(rPath, "Verificando juego...", -1f)
                                            runCatching {
                                                romManager.ensureGameReady(game, games, biosList) { stage, p ->
                                                    replayLaunchState = ReplayLaunchState(rPath, stage, p)
                                                }
                                                replayLaunchState = ReplayLaunchState(rPath, "Descargando replay...", -1f)
                                                val cacheName = rPath.trimStart('/').replace('/', '_')
                                                val dest = java.io.File(context.cacheDir, "replays/$cacheName")
                                                repo.downloadReplay(rPath, dest)
                                            }.onSuccess { replayFile ->
                                                replayLaunchState = null
                                                val entry =
                                                    LeaderboardEntryDto(
                                                        rank = record.rank,
                                                        userId = repo.currentUserId().orEmpty(),
                                                        username = profile?.username ?: "Tú",
                                                        score = record.score,
                                                        achievedAt = record.achievedAt,
                                                        replayPath = rPath,
                                                    )
                                                onLaunchReplay(game, replayFile, entry)
                                            }.onFailure { err ->
                                                replayLaunchState = null
                                                android.widget.Toast
                                                    .makeText(
                                                        context,
                                                        err.toUserMessage("No se pudo abrir el replay"),
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                            }
                                        }
                                    },
                                    onOpenDiagnostics = {
                                        engineStatus = runCatching { pingEngine(nativeLibDir) }.getOrNull()
                                        logs = getRecentLogs()
                                        showDiagnosticsSheet = true
                                    },
                                    onClearCache = {
                                        romManager.clearAllCache()
                                        refreshLocalState()
                                    },
                                    onSignOut = {
                                        performSignOut()
                                    },
                                    onDeleteAccount = {
                                        repo.deleteAccount()
                                        performSignOut()
                                    },
                                )
                                }
                        }
                    }
                }
            }
        }

        if (topOverlay != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = ObsidianBlack,
            ) {
                when (topOverlay) {
                    is AppOverlay.PublicProfile ->
                        androidx.compose.runtime.CompositionLocalProvider(
                            dog.bald.arcade.ui.components.LocalOpenGameDetail provides openGameDetail,
                        ) {
                            PublicProfileScreen(
                                userId = topOverlay.userId,
                                repo = repo,
                                games = games,
                                onBack = { overlayStack = overlayStack.dropLast(1) },
                                onOpenGameRanking = { game ->
                                    overlayStack = overlayStack + AppOverlay.GameRanking(game)
                                },
                                onOpenFollowList = { uid, dir, uname ->
                                    overlayStack = overlayStack + AppOverlay.FollowList(uid, dir, uname)
                                },
                                onFollowChanged = {
                                    scope.launch {
                                        myFollowingIds = runCatching { repo.fetchMyFollowingIds() }.getOrDefault(myFollowingIds)
                                        repo.currentUserId()?.let { uid ->
                                            runCatching { myPublicProfile = repo.fetchPublicProfile(uid) }
                                        }
                                    }
                                },
                            )
                        }
                    is AppOverlay.FollowList ->
                        FollowListScreen(
                            targetUserId = topOverlay.userId,
                            direction = topOverlay.direction,
                            targetUsername = topOverlay.username,
                            repo = repo,
                            onBack = { overlayStack = overlayStack.dropLast(1) },
                            onOpenUserProfile = { uid ->
                                overlayStack = overlayStack + AppOverlay.PublicProfile(uid)
                            },
                            onFollowChanged = {
                                scope.launch {
                                    myFollowingIds = runCatching { repo.fetchMyFollowingIds() }.getOrDefault(myFollowingIds)
                                    repo.currentUserId()?.let { uid ->
                                        runCatching { myPublicProfile = repo.fetchPublicProfile(uid) }
                                    }
                                }
                            },
                        )
                    is AppOverlay.GameDetail ->
                        GameDetailScreen(
                            game = topOverlay.game,
                            onBack = { overlayStack = overlayStack.dropLast(1) },
                            onPlayClick = { game ->
                                if (downloadedFiles.contains(game.romFilename)) {
                                    onLaunchLocalRom(game)
                                } else {
                                    downloadOnly(game, launchAfter = true)
                                }
                            },
                            onRankingClick = { game ->
                                overlayStack = overlayStack + AppOverlay.GameRanking(game)
                            },
                        )
                    is AppOverlay.GameRanking ->
                        GameRankingScreen(
                            game = topOverlay.game,
                            repo = repo,
                            isDownloaded = downloadedFiles.contains(topOverlay.game.romFilename),
                            isDownloading = downloadingGameId == topOverlay.game.id,
                            downloadProgress = if (downloadingGameId == topOverlay.game.id) downloadProgress else 0f,
                            onBack = { overlayStack = overlayStack.dropLast(1) },
                            onPlayOrDownload = {
                                if (downloadedFiles.contains(topOverlay.game.romFilename)) {
                                    onLaunchLocalRom(topOverlay.game)
                                } else {
                                    downloadOnly(topOverlay.game, launchAfter = true)
                                }
                            },
                            onOpenUserProfile = { uid ->
                                overlayStack = overlayStack + AppOverlay.PublicProfile(uid)
                            },
                            onOpenGameDetail = { openGameDetail(topOverlay.game) },
                            replayLaunchState = replayLaunchState,
                            onWatchReplay = { entry ->
                                val rPath = entry.replayPath ?: return@GameRankingScreen
                                if (replayLaunchState != null) return@GameRankingScreen
                                val g = topOverlay.game
                                scope.launch {
                                    replayLaunchState = ReplayLaunchState(rPath, "Verificando juego...", -1f)
                                    runCatching {
                                        romManager.ensureGameReady(g, games, biosList) { stage, p ->
                                            replayLaunchState = ReplayLaunchState(rPath, stage, p)
                                        }
                                        replayLaunchState = ReplayLaunchState(rPath, "Descargando replay...", -1f)
                                        val cacheName = rPath.trimStart('/').replace('/', '_')
                                        val dest = java.io.File(context.cacheDir, "replays/$cacheName")
                                        repo.downloadReplay(rPath, dest)
                                    }.onSuccess { replayFile ->
                                        replayLaunchState = null
                                        val lbEntry =
                                            LeaderboardEntryDto(
                                                rank = entry.rank,
                                                userId = entry.userId,
                                                username = entry.username,
                                                score = entry.score,
                                                achievedAt = entry.achievedAt,
                                                replayPath = rPath,
                                                verificationStatus = entry.verificationStatus,
                                            )
                                        onLaunchReplay(g, replayFile, lbEntry)
                                    }.onFailure { err ->
                                        replayLaunchState = null
                                        android.widget.Toast
                                            .makeText(
                                                context,
                                                err.toUserMessage("No se pudo abrir el replay"),
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
                            },
                        )
                    AppOverlay.WikiHelp ->
                        WikiHelpScreen(
                            onBack = { overlayStack = overlayStack.dropLast(1) },
                        )
                }
            }
        }
    }

    leaderboardGame?.let { game ->
        GameLeaderboardSheet(
            game = game,
            repo = repo,
            onOpenUserProfile = { uid ->
                leaderboardGame = null
                overlayStack = overlayStack + AppOverlay.PublicProfile(uid)
            },
            replayLaunchState = replayLaunchState,
            onWatchReplay = { entry ->
                val rPath = entry.replayPath ?: return@GameLeaderboardSheet
                if (replayLaunchState != null) return@GameLeaderboardSheet
                scope.launch {
                    replayLaunchState = ReplayLaunchState(rPath, "Verificando juego...", -1f)
                    runCatching {
                        romManager.ensureGameReady(game, games, biosList) { stage, p ->
                            replayLaunchState = ReplayLaunchState(rPath, stage, p)
                        }
                        replayLaunchState = ReplayLaunchState(rPath, "Descargando replay...", -1f)
                        val cacheName = rPath.trimStart('/').replace('/', '_')
                        val dest = java.io.File(context.cacheDir, "replays/$cacheName")
                        repo.downloadReplay(rPath, dest)
                    }.onSuccess { replayFile ->
                        replayLaunchState = null
                        leaderboardGame = null
                        onLaunchReplay(game, replayFile, entry)
                    }.onFailure { err ->
                        replayLaunchState = null
                        android.widget.Toast
                            .makeText(
                                context,
                                err.toUserMessage("No se pudo abrir el replay"),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            },
            onDismiss = { leaderboardGame = null },
        )
    }

    postMatchRecordBanner?.let { banner ->
        NewRecordDialog(
            data = banner,
            onDismiss = { postMatchRecordBanner = null },
            onViewRanking = { game ->
                postMatchRecordBanner = null
                overlayStack = overlayStack + AppOverlay.GameRanking(game)
            },
        )
    }

    gamePendingDelete?.let { gameToDelete ->
        AlertDialog(
            onDismissRequest = { gamePendingDelete = null },
            containerColor = CarbonSurface,
            shape = RectangleShape,
            title = {
                Text(
                    text = "ELIMINAR ROM",
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Text(
                    text = "¿Eliminar la ROM local de \"${gameToDelete.title}\" (${gameToDelete.romFilename}) para liberar espacio? Podrás volver a descargarla cuando quieras.",
                    color = SilverGray,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        romManager.deleteRom(gameToDelete.romFilename)
                        refreshLocalState()
                        gamePendingDelete = null
                    },
                    shape = RectangleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = PureWhite,
                        ),
                ) {
                    Text("ELIMINAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { gamePendingDelete = null }) {
                    Text("CANCELAR", color = SilverGray, fontSize = 12.sp)
                }
            },
        )
    }

    if (showDiagnosticsSheet) {
        DiagnosticsSheet(
            engineStatus = engineStatus,
            logs = logs,
            onRefreshLogs = {
                engineStatus = runCatching { pingEngine(nativeLibDir) }.getOrNull()
                logs = getRecentLogs()
            },
            onDismiss = { showDiagnosticsSheet = false },
        )
    }
}

private fun GameDto.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, ignoreCase = true) ||
        system.contains(q, ignoreCase = true) ||
        slug.contains(q, ignoreCase = true)
}

private enum class CatalogSortOption {
    NAME,
    RECENT,
    MOST_DOWNLOADED,
    MOST_PLAYED,
}

@Composable
private fun CatalogTab(
    games: List<GameDto>,
    downloadedFiles: Set<String>,
    downloadingGameId: String?,
    downloadProgress: Float,
    busy: Boolean,
    rankingSummaries: Map<String, GameRankingSummaryDto> = emptyMap(),
    onRefresh: () -> Unit,
    onDownload: (GameDto) -> Unit,
    onPlay: (GameDto) -> Unit,
    onGameDetail: (GameDto) -> Unit,
    onLeaderboard: (GameDto) -> Unit,
    onDeleteClick: (GameDto) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedSort by remember { mutableStateOf(CatalogSortOption.NAME) }
    var downloadedOnly by remember { mutableStateOf(false) }
    var launchingGameId by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    launchingGameId = null
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(launchingGameId) {
        if (launchingGameId != null) {
            kotlinx.coroutines.delay(3000L)
            launchingGameId = null
        }
    }

    val filtered =
        remember(games, searchQuery, selectedSort, downloadedOnly, downloadedFiles) {
            val comparator =
                when (selectedSort) {
                    CatalogSortOption.NAME -> compareBy<GameDto> { it.title.lowercase() }
                    CatalogSortOption.RECENT ->
                        compareByDescending<GameDto> { it.createdAt.orEmpty() }
                            .thenBy { it.title.lowercase() }
                    CatalogSortOption.MOST_DOWNLOADED ->
                        compareByDescending<GameDto> { it.downloadCount }
                            .thenBy { it.title.lowercase() }
                    CatalogSortOption.MOST_PLAYED ->
                        compareByDescending<GameDto> { it.totalPlaySeconds }
                            .thenBy { it.title.lowercase() }
                }
            games
                .asSequence()
                .filter { it.matchesQuery(searchQuery) }
                .filter { !downloadedOnly || downloadedFiles.contains(it.romFilename) }
                .sortedWith(comparator)
                .toList()
        }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        ArcadeSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Buscar en catálogo...",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val isRecent = selectedSort == CatalogSortOption.RECENT
            CatalogFilterChip(
                label = "Nuevos",
                selected = isRecent,
                onClick = {
                    selectedSort = if (isRecent) CatalogSortOption.NAME else CatalogSortOption.RECENT
                },
                modifier = Modifier.weight(1f),
            )
            val isMostDownloaded = selectedSort == CatalogSortOption.MOST_DOWNLOADED
            CatalogFilterChip(
                label = "Más descargados",
                selected = isMostDownloaded,
                onClick = {
                    selectedSort = if (isMostDownloaded) CatalogSortOption.NAME else CatalogSortOption.MOST_DOWNLOADED
                },
                modifier = Modifier.weight(1f),
            )
            val isMostPlayed = selectedSort == CatalogSortOption.MOST_PLAYED
            CatalogFilterChip(
                label = "Más jugados",
                selected = isMostPlayed,
                onClick = {
                    selectedSort = if (isMostPlayed) CatalogSortOption.NAME else CatalogSortOption.MOST_PLAYED
                },
                modifier = Modifier.weight(1f),
            )
            CatalogFilterChip(
                label = "Biblioteca",
                selected = downloadedOnly,
                onClick = { downloadedOnly = !downloadedOnly },
                modifier = Modifier.weight(1f),
            )
        }

        if (filtered.isEmpty() && (searchQuery.isNotBlank() || downloadedOnly)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (searchQuery.isNotBlank()) "Sin resultados para \"${searchQuery.trim()}\"" else "No hay juegos descargados",
                        color = PureWhite,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (searchQuery.isNotBlank()) "Prueba con otro término o limpia los filtros." else "Descarga juegos del catálogo para verlos aquí.",
                        color = MutedGray,
                        fontSize = 13.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            searchQuery = ""
                            downloadedOnly = false
                        },
                        border = BorderStroke(1.dp, SteelBorder),
                    ) {
                        Text("LIMPIAR FILTROS", color = PureWhite, fontSize = 12.sp)
                    }
                }
            }
        } else if (filtered.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Catálogo vacío por ahora",
                        color = PureWhite,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Agrega juegos en la tabla `games` de Supabase para descargarlos vía IPFS.",
                        color = MutedGray,
                        fontSize = 13.sp,
                    )
                    OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, SteelBorder)) {
                        Text("ACTUALIZAR CATÁLOGO", color = PureWhite, fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.id }) { game ->
                    val isDownloaded = downloadedFiles.contains(game.romFilename)
                    val hasLeaderboard = !game.scoreConfig?.lboard.isNullOrBlank()
                    ArcadeGameCard(
                        game = game,
                        isDownloaded = isDownloaded,
                        isDownloading = downloadingGameId == game.id,
                        isLaunching = launchingGameId == game.id,
                        downloadProgress = if (downloadingGameId == game.id) downloadProgress else 0f,
                        busy = busy,
                        rankingSummary = rankingSummaries[game.id],
                        onPrimaryAction = {
                            if (isDownloaded) {
                                launchingGameId = game.id
                                onPlay(game)
                            } else {
                                onDownload(game)
                            }
                        },
                        onDetailClick = { onGameDetail(game) },
                        onLeaderboardClick =
                            if (hasLeaderboard) {
                                { onLeaderboard(game) }
                            } else {
                                null
                            },
                        onDeleteClick =
                            if (isDownloaded && !busy) {
                                { onDeleteClick(game) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomsTab(
    rooms: List<RoomDto>,
    allGames: List<GameDto>,
    libraryGames: List<GameDto>,
    lanNsd: LanNsdManager,
    followingIds: Set<String> = emptySet(),
    roomNoticeMsg: String?,
    onDismissRoomNotice: () -> Unit,
    onGoToCatalog: () -> Unit,
    onRefresh: () -> Unit,
    onLookupRoom: suspend (String) -> RoomDto?,
    onCreateRoom: (GameDto, String, Boolean) -> Unit,
    onJoinRoom: (RoomDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val discoveredLan by lanNsd.discoveredRooms.collectAsState()
    var selectedGame by remember(libraryGames) { mutableStateOf(libraryGames.firstOrNull()) }
    var selectedMode by remember { mutableStateOf("lan") }
    var isPrivateRoom by remember { mutableStateOf(false) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var joinBusy by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        lanNsd.startDiscovery()
        onDispose { lanNsd.stopDiscovery() }
    }

    if (showCreateRoomDialog) {
        val filteredGames =
            remember(libraryGames, pickerQuery) {
                if (pickerQuery.isBlank()) {
                    libraryGames
                } else {
                    libraryGames.filter {
                        it.title.contains(pickerQuery, ignoreCase = true) ||
                            it.system.contains(pickerQuery, ignoreCase = true)
                    }
                }
            }
        AlertDialog(
            onDismissRequest = { showCreateRoomDialog = false },
            containerColor = CarbonSurface,
            title = { Text("Crear Sala", color = PureWhite, fontWeight = FontWeight.Bold) },
            text = {
                if (libraryGames.isEmpty()) {
                    Text(
                        text = "Descarga un juego en el Catálogo para poder crear una sala.",
                        color = MutedGray,
                        fontSize = 13.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            val isLan = selectedMode == "lan"
                            Button(
                                onClick = { selectedMode = "lan" },
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = if (isLan) PureWhite else ObsidianBlack,
                                        contentColor = if (isLan) PureBlack else SilverGray,
                                    ),
                                border = if (isLan) null else BorderStroke(1.dp, CharcoalBorder),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("LAN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Button(
                                onClick = { selectedMode = "online" },
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = if (!isLan) PureWhite else ObsidianBlack,
                                        contentColor = if (!isLan) PureBlack else SilverGray,
                                    ),
                                border = if (!isLan) null else BorderStroke(1.dp, CharcoalBorder),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("ONLINE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { isPrivateRoom = false },
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = if (!isPrivateRoom) PureWhite else ObsidianBlack,
                                        contentColor = if (!isPrivateRoom) PureBlack else SilverGray,
                                    ),
                                border = if (!isPrivateRoom) null else BorderStroke(1.dp, CharcoalBorder),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("PÚBLICA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Button(
                                onClick = { isPrivateRoom = true },
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = if (isPrivateRoom) PureWhite else ObsidianBlack,
                                        contentColor = if (isPrivateRoom) PureBlack else SilverGray,
                                    ),
                                border = if (isPrivateRoom) null else BorderStroke(1.dp, CharcoalBorder),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("PRIVADA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        if (isPrivateRoom) {
                            Text(
                                text = "No aparecerá en el listado; solo accesible por código o ID.",
                                color = MutedGray,
                                fontSize = 11.sp,
                            )
                        }
                        Text("Elige un juego de tu biblioteca:", color = SilverGray, fontSize = 12.sp)
                        OutlinedTextField(
                            value = pickerQuery,
                            onValueChange = { pickerQuery = it },
                            placeholder = { Text("Buscar juego...", fontSize = 12.sp) },
                            singleLine = true,
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PureWhite,
                                    unfocusedBorderColor = CharcoalBorder,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyColumn(modifier = Modifier.height(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filteredGames, key = { it.id }) { g ->
                                Card(
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = if (selectedGame?.id == g.id) GraphiteSurface else ObsidianBlack,
                                        ),
                                    border =
                                        BorderStroke(
                                            1.dp,
                                            if (selectedGame?.id == g.id) PureWhite else CharcoalBorder,
                                        ),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedGame = g },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        GameCoverImage(
                                            slug = g.slug,
                                            contentDescription = g.title,
                                            modifier = Modifier.size(42.dp),
                                        )
                                        Column {
                                            Text(g.title, color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("${g.system} · ${g.maxPlayers}P", color = MutedGray, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (libraryGames.isEmpty()) {
                    Button(
                        onClick = {
                            showCreateRoomDialog = false
                            onGoToCatalog()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                    ) {
                        Text("IR AL CATÁLOGO", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        enabled = selectedGame != null,
                        onClick = {
                            selectedGame?.let { game ->
                                showCreateRoomDialog = false
                                onCreateRoom(game, selectedMode, isPrivateRoom)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                    ) {
                        Text("CREAR SALA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRoomDialog = false }) {
                    Text("CANCELAR", color = SilverGray)
                }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Salas LAN y Online", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refrescar",
                    tint = PureWhite,
                )
            }
        }

        if (roomNoticeMsg != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                border = BorderStroke(1.dp, PureWhite),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = roomNoticeMsg,
                        color = PureWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissRoomNotice) {
                        Text("OK", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        Button(
            onClick = {
                pickerQuery = ""
                isPrivateRoom = false
                if (selectedGame == null) {
                    selectedGame = libraryGames.firstOrNull()
                }
                showCreateRoomDialog = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("CREAR SALA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        // Unirse por código o ID de sala (consulta directa a Supabase)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = joinCode,
                onValueChange = {
                    joinCode = it.uppercase()
                    joinError = null
                },
                placeholder = { Text("Código o ID (ej. 4821A)", fontSize = 12.sp) },
                singleLine = true,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PureWhite,
                        unfocusedBorderColor = CharcoalBorder,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                    ),
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = joinCode.isNotBlank() && !joinBusy,
                onClick = {
                    scope.launch {
                        joinBusy = true
                        joinError = null
                        val codeClean = joinCode.trim().removePrefix("#")
                        val found = runCatching { onLookupRoom(codeClean) }.getOrNull()
                        joinBusy = false
                        if (found != null) {
                            onJoinRoom(found)
                        } else {
                            joinError = "No se encontró sala activa #$codeClean"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
            ) {
                Text(if (joinBusy) "..." else "UNIRSE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (joinError != null) {
            Text(joinError ?: "", color = SilverGray, fontSize = 12.sp)
        }

        val lanCodes =
            remember(discoveredLan) {
                discoveredLan.map { it.roomCode.uppercase() }.toSet()
            }
        var onlyFollowingRooms by remember { mutableStateOf(false) }
        val otherRooms =
            remember(rooms, lanCodes, onlyFollowingRooms, followingIds) {
                rooms
                    .filterNot { lanCodes.contains(it.code.uppercase()) }
                    .filter { !onlyFollowingRooms || followingIds.contains(it.hostId) }
            }
        if (followingIds.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape),
            ) {
                listOf(false to "TODAS LAS SALAS", true to "SOLO SEGUIDOS").forEach { (flagVal, label) ->
                    val active = onlyFollowingRooms == flagVal
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .background(if (active) PureWhite else CarbonSurface)
                                .clickable { onlyFollowingRooms = flagVal }
                                .padding(vertical = 8.dp),
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
        }

        if (discoveredLan.isNotEmpty()) {
            Text("SALAS DETECTADAS EN WIFI (NSD):", color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            discoveredLan.forEach { lan ->
                val lanGame = allGames.firstOrNull { it.title.equals(lan.gameTitle, ignoreCase = true) }

                fun joinLan() {
                    scope.launch {
                        joinError = null
                        val found =
                            rooms.firstOrNull { it.code.equals(lan.roomCode, ignoreCase = true) }
                                ?: runCatching { onLookupRoom(lan.roomCode) }.getOrNull()
                        if (found != null) {
                            onJoinRoom(found)
                        } else {
                            joinError = "La sala LAN #${lan.roomCode} ya no está disponible"
                        }
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                    border = BorderStroke(1.dp, PureWhite),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { joinLan() },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GameCoverImage(
                            slug = lanGame?.slug,
                            contentDescription = lan.gameTitle,
                            modifier = Modifier.size(44.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${lan.gameTitle} · #${lan.roomCode}", color = PureWhite, fontWeight = FontWeight.Bold)
                            Text("${lan.hostIp}:${lan.udpPort}", color = MutedGray, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { joinLan() },
                            colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                        ) {
                            Text("UNIRSE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (otherRooms.isEmpty() && discoveredLan.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "No hay salas abiertas. Toca \"Crear sala\" para iniciar una partida LAN o Online.",
                    color = MutedGray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else if (otherRooms.isNotEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(otherRooms, key = { it.id }) { room ->
                    val roomGame = allGames.firstOrNull { it.id == room.gameId }
                    val roomGameTitle = roomGame?.title ?: "Arcade"
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                        border = BorderStroke(1.dp, CharcoalBorder),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJoinRoom(room) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GameCoverImage(
                                slug = roomGame?.slug,
                                contentDescription = roomGameTitle,
                                modifier = Modifier.size(48.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("$roomGameTitle · #${room.code}", color = PureWhite, fontWeight = FontWeight.Bold)
                                    if (followingIds.contains(room.hostId)) {
                                        Text(
                                            text = "[SIGUIENDO]",
                                            color = PureBlack,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier =
                                                Modifier
                                                    .background(PureWhite, RectangleShape)
                                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    "${room.mode.uppercase()} · Jugadores: ${room.slotsJson.size}/${room.maxPlayers}",
                                    color = MutedGray,
                                    fontSize = 12.sp,
                                )
                            }
                            Button(
                                onClick = { onJoinRoom(room) },
                                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                            ) {
                                Text("ENTRAR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else CarbonSurface,
        border =
            BorderStroke(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else CharcoalBorder,
            ),
        modifier = modifier,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) PureWhite else MutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}
