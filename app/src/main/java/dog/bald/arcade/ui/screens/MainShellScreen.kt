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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.GameBiosDto
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.IpfsRomManager
import dog.bald.arcade.data.ProfileDto
import dog.bald.arcade.data.RoomDto
import dog.bald.arcade.net.DiscoveredLanRoom
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.balddog_android.EngineStatus
import uniffi.balddog_android.discoverPublicUdpEndpoint
import uniffi.balddog_android.getRecentLogs
import uniffi.balddog_android.pingEngine

private enum class MainTab(val label: String, val icon: ImageVector) {
    CATALOG("Catálogo", Icons.Default.SportsEsports),
    ROOMS("Salas", Icons.Default.Groups),
    LIBRARY("Biblioteca", Icons.Default.Bookmark),
    PROFILE("Perfil", Icons.Default.Person),
}

@Composable
fun MainShellScreen(
    repo: ArcadeRepository,
    romManager: IpfsRomManager,
    lanNsd: LanNsdManager,
    nativeLibDir: String,
    onSignedOut: () -> Unit,
    onLaunchLocalRom: (GameDto) -> Unit,
    onLaunchNetplayMatch: (GameDto, RoomDto, Int, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var games by remember { mutableStateOf<List<GameDto>>(emptyList()) }
    var biosList by remember { mutableStateOf<List<GameBiosDto>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<RoomDto>>(emptyList()) }
    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    var engineStatus by remember { mutableStateOf<EngineStatus?>(null) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var syncError by remember { mutableStateOf<String?>(null) }

    var activeRoom by remember { mutableStateOf<RoomDto?>(null) }
    var myUdpPort by remember { mutableIntStateOf(7000) }
    var myEndpoint by remember { mutableStateOf("") }

    var downloadingGameId by remember { mutableStateOf<String?>(null) }
    var downloadStage by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedFiles by remember { mutableStateOf(romManager.listDownloadedFilenames()) }
    var cacheBytes by remember { mutableLongStateOf(romManager.totalCacheBytes()) }

    fun refreshLocalState() {
        downloadedFiles = romManager.listDownloadedFilenames()
        cacheBytes = romManager.totalCacheBytes()
    }

    fun refreshAll() {
        scope.launch {
            syncError = null
            runCatching {
                engineStatus = pingEngine(nativeLibDir)
                logs = getRecentLogs()
            }.onFailure { t ->
                syncError = t.message ?: "Error conectando con motor nativo"
            }
            try {
                games = repo.fetchGames()
                biosList = repo.fetchBios()
                rooms = repo.fetchOpenRooms()
                profile = repo.fetchMyProfile()
                refreshLocalState()
            } catch (t: Throwable) {
                val msg = t.message ?: "Error sincronizando catálogo"
                syncError = if (syncError == null) msg else "$syncError\n$msg"
            }
        }
    }

    fun downloadOnly(game: GameDto) {
        if (downloadingGameId != null) return
        scope.launch {
            downloadingGameId = game.id
            downloadProgress = 0f
            downloadStage = "Preparando..."
            syncError = null
            try {
                romManager.ensureGameReady(
                    game = game,
                    allGames = games,
                    allBios = biosList
                ) { stage, progress ->
                    downloadStage = stage
                    downloadProgress = progress
                }
                refreshLocalState()
            } catch (t: Throwable) {
                syncError = t.message ?: "Error descargando ROM"
            } finally {
                downloadingGameId = null
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    // Auto-refrescar salas cada 3s mientras el usuario está en la pestaña Salas
    LaunchedEffect(selectedTab, activeRoom) {
        if (MainTab.entries[selectedTab] == MainTab.ROOMS && activeRoom == null) {
            while (true) {
                runCatching { rooms = repo.fetchOpenRooms() }
                delay(3000)
            }
        }
    }

    val currentRoom = activeRoom
    if (currentRoom != null) {
        val roomGame = games.firstOrNull { it.id == currentRoom.gameId }
        RoomLobbyScreen(
            repo = repo,
            lanNsd = lanNsd,
            initialRoom = currentRoom,
            game = roomGame,
            myUsername = profile?.username ?: "Jugador",
            myEndpoint = myEndpoint,
            onLeave = {
                activeRoom = null
                refreshAll()
            },
            onStartMatch = { startedRoom, mySlot ->
                if (roomGame != null) {
                    scope.launch {
                        runCatching {
                            romManager.ensureGameReady(roomGame, games, biosList) { stage, p ->
                                downloadStage = stage
                                downloadProgress = p
                            }
                            activeRoom = null
                            onLaunchNetplayMatch(roomGame, startedRoom, mySlot, myUdpPort)
                        }.onFailure { syncError = it.message }
                    }
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = ObsidianBlack,
        bottomBar = {
            NavigationBar(containerColor = CarbonSurface) {
                MainTab.entries.forEachIndexed { idx, tab ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PureWhite,
                            selectedTextColor = PureWhite,
                            unselectedIconColor = MutedGray,
                            unselectedTextColor = MutedGray,
                            indicatorColor = GraphiteSurface
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "bald.dog ARCADE",
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = profile?.username ?: "Jugador",
                    color = SilverGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (syncError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = syncError ?: "", color = SilverGray, fontSize = 12.sp)
            }

            if (downloadingGameId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$downloadStage (${(downloadProgress * 100).toInt()}%)",
                    color = PureWhite,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp
                )
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    color = PureWhite,
                    trackColor = GraphiteSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (MainTab.entries[selectedTab]) {
                MainTab.CATALOG -> CatalogTab(
                    games = games,
                    downloadedFiles = downloadedFiles,
                    busy = downloadingGameId != null,
                    onRefresh = { refreshAll() },
                    onDownload = { downloadOnly(it) },
                    onPlay = { onLaunchLocalRom(it) }
                )

                MainTab.ROOMS -> RoomsTab(
                    rooms = rooms,
                    allGames = games,
                    libraryGames = games.filter { downloadedFiles.contains(it.romFilename) },
                    lanNsd = lanNsd,
                    onGoToCatalog = { selectedTab = 0 },
                    onRefresh = { refreshAll() },
                    onLookupRoom = { codeOrId -> repo.fetchRoomByCodeOrId(codeOrId) },
                    onCreateRoom = { game, mode ->
                        scope.launch {
                            syncError = null
                            try {
                                val port = (7000..7099).random()
                                myUdpPort = port
                                val localIp = lanNsd.getLocalIpv4Address()
                                val endpoint = if (mode == "lan") {
                                    "${localIp ?: "127.0.0.1"}:$port"
                                } else {
                                    withContext(Dispatchers.IO) {
                                        runCatching { discoverPublicUdpEndpoint(port.toUShort()) }
                                            .getOrElse { "${localIp ?: "127.0.0.1"}:$port" }
                                    }
                                }
                                myEndpoint = endpoint
                                val created = repo.createRoom(
                                    gameId = game.id,
                                    mode = mode,
                                    maxPlayers = game.maxPlayers,
                                    username = profile?.username ?: "Host",
                                    endpoint = endpoint,
                                    localIp = localIp,
                                    localPort = port
                                )
                                activeRoom = created
                            } catch (t: Throwable) {
                                syncError = t.message ?: "Error creando sala"
                            }
                        }
                    },
                    onJoinRoom = { room ->
                        scope.launch {
                            val port = (7100..7199).random()
                            myUdpPort = port
                            val localIp = lanNsd.getLocalIpv4Address()
                            myEndpoint = if (room.mode == "lan") {
                                "${localIp ?: "127.0.0.1"}:$port"
                            } else {
                                withContext(Dispatchers.IO) {
                                    runCatching { discoverPublicUdpEndpoint(port.toUShort()) }
                                        .getOrElse { "${localIp ?: "127.0.0.1"}:$port" }
                                }
                            }
                            activeRoom = room
                        }
                    }
                )

                MainTab.LIBRARY -> LibraryTab(
                    games = games.filter { downloadedFiles.contains(it.romFilename) },
                    onPlay = { onLaunchLocalRom(it) },
                    onDelete = { game ->
                        romManager.deleteRom(game.romFilename)
                        refreshLocalState()
                    }
                )

                MainTab.PROFILE -> ProfileTab(
                    profile = profile,
                    engineStatus = engineStatus,
                    cacheBytes = cacheBytes,
                    logs = logs,
                    onRefreshLogs = {
                        engineStatus = runCatching { pingEngine(nativeLibDir) }.getOrNull()
                        logs = getRecentLogs()
                    },
                    onClearCache = {
                        romManager.clearAllCache()
                        refreshLocalState()
                    },
                    onSignOut = {
                        scope.launch {
                            runCatching { repo.signOut() }
                            onSignedOut()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CatalogTab(
    games: List<GameDto>,
    downloadedFiles: Set<String>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onDownload: (GameDto) -> Unit,
    onPlay: (GameDto) -> Unit
) {
    var filterSystem by remember { mutableStateOf("ALL") }
    val systems = listOf("ALL", "NEOGEO", "CPS2", "CO-OP")

    val filtered = remember(games, filterSystem) {
        when (filterSystem) {
            "ALL" -> games
            "CO-OP" -> games.filter { it.isCoop }
            else -> games.filter { it.system.equals(filterSystem, ignoreCase = true) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            systems.forEach { sys ->
                FilterChip(
                    selected = filterSystem == sys,
                    onClick = { filterSystem = sys },
                    label = { Text(sys, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PureWhite,
                        selectedLabelColor = PureBlack,
                        containerColor = CarbonSurface,
                        labelColor = MutedGray
                    )
                )
            }
        }

        if (filtered.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Catálogo vacío por ahora", color = PureWhite, fontWeight = FontWeight.Bold)
                    Text(
                        "Agrega juegos en la tabla `games` de Supabase para descargarlos vía IPFS.",
                        color = MutedGray,
                        fontSize = 13.sp
                    )
                    OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, SteelBorder)) {
                        Text("ACTUALIZAR CATÁLOGO", color = PureWhite)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { game ->
                    val isLocal = downloadedFiles.contains(game.romFilename)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                        border = BorderStroke(1.dp, CharcoalBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GameCoverImage(
                                slug = game.slug,
                                contentDescription = game.title,
                                modifier = Modifier.size(56.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(game.title, color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "${game.system} · ${game.maxPlayers}P${if (game.isCoop) " · CO-OP" else ""}",
                                    color = MutedGray,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                enabled = !busy,
                                onClick = { if (isLocal) onPlay(game) else onDownload(game) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isLocal) PureWhite else GraphiteSurface,
                                    contentColor = if (isLocal) PureBlack else PureWhite
                                )
                            ) {
                                Text(
                                    if (isLocal) "JUGAR" else "DESCARGAR",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
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
    onGoToCatalog: () -> Unit,
    onRefresh: () -> Unit,
    onLookupRoom: suspend (String) -> RoomDto?,
    onCreateRoom: (GameDto, String) -> Unit,
    onJoinRoom: (RoomDto) -> Unit
) {
    val scope = rememberCoroutineScope()
    val discoveredLan by lanNsd.discoveredRooms.collectAsState()
    var selectedGame by remember(libraryGames) { mutableStateOf(libraryGames.firstOrNull()) }
    var showGamePicker by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var joinBusy by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        lanNsd.startDiscovery()
        onDispose { lanNsd.stopDiscovery() }
    }

    if (showGamePicker) {
        val filteredGames = remember(libraryGames, pickerQuery) {
            if (pickerQuery.isBlank()) libraryGames
            else libraryGames.filter {
                it.title.contains(pickerQuery, ignoreCase = true) ||
                    it.system.contains(pickerQuery, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { showGamePicker = false },
            containerColor = CarbonSurface,
            title = { Text("Elegir Juego de tu Biblioteca", color = PureWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        placeholder = { Text("Buscar juego...", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PureWhite,
                            unfocusedBorderColor = CharcoalBorder,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.height(280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredGames, key = { it.id }) { g ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedGame?.id == g.id) GraphiteSurface else ObsidianBlack
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedGame?.id == g.id) PureWhite else CharcoalBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedGame = g
                                        showGamePicker = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GameCoverImage(
                                        slug = g.slug,
                                        contentDescription = g.title,
                                        modifier = Modifier.size(42.dp)
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
            },
            confirmButton = {
                TextButton(onClick = { showGamePicker = false }) {
                    Text("CERRAR", color = SilverGray)
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Salas LAN y Online", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, SteelBorder)) {
                Text("REFRESCAR", color = PureWhite, fontSize = 11.sp)
            }
        }

        // Selector de juego para crear sala (solo juegos descargados en Biblioteca)
        if (libraryGames.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Descarga un juego en el Catálogo para poder crear una sala.",
                        color = MutedGray,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onGoToCatalog, border = BorderStroke(1.dp, SteelBorder)) {
                        Text("CATÁLOGO", color = PureWhite, fontSize = 11.sp)
                    }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showGamePicker = true }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GameCoverImage(
                        slug = selectedGame?.slug,
                        contentDescription = selectedGame?.title,
                        modifier = Modifier.size(48.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("JUEGO DE TU BIBLIOTECA:", color = MutedGray, fontSize = 10.sp)
                        Text(
                            text = selectedGame?.title ?: "",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                        if (selectedGame != null) {
                            Text(
                                "${selectedGame?.system} · Máx ${selectedGame?.maxPlayers}P",
                                color = MutedGray,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text("CAMBIAR", color = SilverGray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        selectedGame?.let { game ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onCreateRoom(game, "lan") },
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CREAR LAN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = { onCreateRoom(game, "online") },
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteSurface, contentColor = PureWhite),
                    border = BorderStroke(1.dp, SteelBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CREAR ONLINE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // Unirse por código o ID de sala (consulta directa a Supabase)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = joinCode,
                onValueChange = {
                    joinCode = it.uppercase()
                    joinError = null
                },
                placeholder = { Text("Código o ID (ej. 4821A)", fontSize = 12.sp) },
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
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
            ) {
                Text(if (joinBusy) "..." else "UNIRSE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (joinError != null) {
            Text(joinError ?: "", color = SilverGray, fontSize = 12.sp)
        }

        val lanCodes = remember(discoveredLan) {
            discoveredLan.map { it.roomCode.uppercase() }.toSet()
        }
        val otherRooms = remember(rooms, lanCodes) {
            rooms.filterNot { lanCodes.contains(it.code.uppercase()) }
        }

        if (discoveredLan.isNotEmpty()) {
            Text("SALAS DETECTADAS EN WIFI (NSD):", color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            discoveredLan.forEach { lan ->
                val lanGame = allGames.firstOrNull { it.title.equals(lan.gameTitle, ignoreCase = true) }
                fun joinLan() {
                    scope.launch {
                        joinError = null
                        val found = rooms.firstOrNull { it.code.equals(lan.roomCode, ignoreCase = true) }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { joinLan() }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GameCoverImage(
                            slug = lanGame?.slug,
                            contentDescription = lan.gameTitle,
                            modifier = Modifier.size(44.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${lan.gameTitle} · #${lan.roomCode}", color = PureWhite, fontWeight = FontWeight.Bold)
                            Text("${lan.hostIp}:${lan.udpPort}", color = MutedGray, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { joinLan() },
                            colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No hay salas abiertas. Selecciona un juego arriba y crea una sala LAN o Online.",
                    color = MutedGray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJoinRoom(room) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GameCoverImage(
                                slug = roomGame?.slug,
                                contentDescription = roomGameTitle,
                                modifier = Modifier.size(48.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("$roomGameTitle · #${room.code}", color = PureWhite, fontWeight = FontWeight.Bold)
                                Text(
                                    "${room.mode.uppercase()} · Jugadores: ${room.slotsJson.size}/${room.maxPlayers}",
                                    color = MutedGray,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = { onJoinRoom(room) },
                                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
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
private fun LibraryTab(
    games: List<GameDto>,
    onPlay: (GameDto) -> Unit,
    onDelete: (GameDto) -> Unit
) {
    if (games.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CarbonSurface),
            border = BorderStroke(1.dp, CharcoalBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Biblioteca Local vacía", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Descarga un juego desde el Catálogo para verlo aquí y jugarlo sin conexión.",
                    color = MutedGray,
                    fontSize = 13.sp
                )
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(games, key = { it.id }) { game ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                    border = BorderStroke(1.dp, CharcoalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GameCoverImage(
                            slug = game.slug,
                            contentDescription = game.title,
                            modifier = Modifier.size(52.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(game.title, color = PureWhite, fontWeight = FontWeight.Bold)
                            Text("${game.system} · Verificado SHA256", color = MutedGray, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onDelete(game) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Borrar ROM", tint = SilverGray)
                            }
                            Button(
                                onClick = { onPlay(game) },
                                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
                            ) {
                                Text("JUGAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTab(
    profile: ProfileDto?,
    engineStatus: EngineStatus?,
    cacheBytes: Long,
    logs: List<String>,
    onRefreshLogs: () -> Unit,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit
) {
    val cacheMb = cacheBytes / (1024.0 * 1024.0)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CarbonSurface),
            border = BorderStroke(1.dp, CharcoalBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Perfil: ${profile?.username ?: "Invitado"}", color = PureWhite, fontWeight = FontWeight.Bold)
                Text(
                    "Motor Rust v${engineStatus?.engineVersion ?: "?"} · ${engineStatus?.targetArch ?: "?"} · FBNeo: ${if (engineStatus?.fbneoReady == true) "LISTO" else "NO DETECTADO"}",
                    color = SilverGray,
                    fontSize = 12.sp
                )
                Text(
                    "Caché local (ROMs/BIOS/States): ${"%.1f".format(cacheMb)} MB",
                    color = MutedGray,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshLogs, border = BorderStroke(1.dp, SteelBorder)) {
                        Text("PING", color = PureWhite, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onClearCache, border = BorderStroke(1.dp, SteelBorder)) {
                        Text("LIMPIAR CACHÉ", color = SilverGray, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onSignOut, border = BorderStroke(1.dp, CharcoalBorder)) {
                        Text("SALIR", color = MutedGray, fontSize = 11.sp)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(CarbonSurface, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        "LOGS INTERNOS (RUST):",
                        color = MutedGray,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily
                    )
                }
                items(logs) { line ->
                    Text(
                        line,
                        color = SilverGray,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily
                    )
                }
            }
        }
    }
}
