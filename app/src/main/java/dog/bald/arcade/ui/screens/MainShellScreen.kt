package dog.bald.arcade.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsEsports
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeMagenta
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.CoinYellow
import dog.bald.arcade.ui.theme.DeepPurple
import dog.bald.arcade.ui.theme.LeatherBlack
import dog.bald.arcade.ui.theme.RetroIvory
import dog.bald.arcade.ui.theme.SteelGray
import kotlinx.coroutines.launch
import uniffi.balddog_android.EngineStatus
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
    nativeLibDir: String,
    onSignedOut: () -> Unit,
    onLaunchLocalRom: (GameDto) -> Unit
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
            try {
                engineStatus = pingEngine(nativeLibDir)
                logs = getRecentLogs()
                games = repo.fetchGames()
                biosList = repo.fetchBios()
                rooms = repo.fetchOpenRooms()
                profile = repo.fetchMyProfile()
                refreshLocalState()
            } catch (t: Throwable) {
                syncError = t.message ?: "Error sincronizando datos"
            }
        }
    }

    fun downloadAndPlay(game: GameDto) {
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
                onLaunchLocalRom(game)
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

    Scaffold(
        containerColor = ArcadeBlack,
        bottomBar = {
            NavigationBar(containerColor = LeatherBlack) {
                MainTab.entries.forEachIndexed { idx, tab ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CoinYellow,
                            selectedTextColor = CoinYellow,
                            unselectedIconColor = SteelGray,
                            unselectedTextColor = SteelGray,
                            indicatorColor = DeepPurple
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
                    color = CoinYellow,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = profile?.username ?: "Jugador",
                    color = ArcadeMagenta,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (syncError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = syncError ?: "", color = ArcadeOrange, fontSize = 12.sp)
            }

            if (downloadingGameId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$downloadStage (${(downloadProgress * 100).toInt()}%)",
                    color = CoinYellow,
                    fontSize = 12.sp
                )
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    color = CoinYellow,
                    trackColor = DeepPurple,
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
                    onPlay = { downloadAndPlay(it) }
                )

                MainTab.ROOMS -> RoomsTab(rooms = rooms, onRefresh = { refreshAll() })

                MainTab.LIBRARY -> LibraryTab(
                    games = games.filter { downloadedFiles.contains(it.romFilename) },
                    onPlay = { downloadAndPlay(it) },
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
                        selectedContainerColor = ArcadeMagenta,
                        selectedLabelColor = RetroIvory,
                        containerColor = LeatherBlack,
                        labelColor = SteelGray
                    )
                )
            }
        }

        if (filtered.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = LeatherBlack),
                border = BorderStroke(1.dp, DeepPurple),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Catálogo vacío por ahora", color = RetroIvory, fontWeight = FontWeight.Bold)
                    Text(
                        "Agrega juegos en la tabla `games` de Supabase para descargarlos vía IPFS.",
                        color = SteelGray,
                        fontSize = 13.sp
                    )
                    OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, CoinYellow)) {
                        Text("ACTUALIZAR CATÁLOGO", color = CoinYellow)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { game ->
                    val isLocal = downloadedFiles.contains(game.romFilename)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LeatherBlack),
                        border = BorderStroke(1.dp, ArcadeMagenta.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(game.title, color = RetroIvory, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "${game.system} · ${game.maxPlayers}P${if (game.isCoop) " · CO-OP" else ""}",
                                    color = SteelGray,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                enabled = !busy,
                                onClick = { onPlay(game) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isLocal) CoinYellow else ArcadeOrange,
                                    contentColor = ArcadeBlack
                                )
                            ) {
                                Text(
                                    if (isLocal) "JUGAR" else "DESCARGAR",
                                    fontWeight = FontWeight.ExtraBold,
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
private fun RoomsTab(rooms: List<RoomDto>, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Salas LAN y Online", color = RetroIvory, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, ArcadeMagenta)) {
                Text("REFRESCAR", color = ArcadeMagenta, fontSize = 11.sp)
            }
        }

        if (rooms.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = LeatherBlack),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No hay salas abiertas. En las Fases 6 y 7 activaremos el descubrimiento WiFi (NsdManager) y Netplay P2P.",
                    color = SteelGray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rooms, key = { it.id }) { room ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LeatherBlack),
                        border = BorderStroke(1.dp, ArcadeOrange.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Sala #${room.code} (${room.mode.uppercase()})", color = CoinYellow, fontWeight = FontWeight.Bold)
                            Text("Estado: ${room.status} · Máx ${room.maxPlayers}P", color = SteelGray, fontSize = 12.sp)
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
            colors = CardDefaults.cardColors(containerColor = LeatherBlack),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Biblioteca Local vacía", color = RetroIvory, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Descarga un juego desde el Catálogo para verlo aquí y jugarlo sin conexión.",
                    color = SteelGray,
                    fontSize = 13.sp
                )
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(games, key = { it.id }) { game ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = LeatherBlack),
                    border = BorderStroke(1.dp, CoinYellow.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(game.title, color = RetroIvory, fontWeight = FontWeight.Bold)
                            Text("${game.system} · Verificado SHA256", color = SteelGray, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onDelete(game) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Borrar ROM", tint = ArcadeOrange)
                            }
                            Button(
                                onClick = { onPlay(game) },
                                colors = ButtonDefaults.buttonColors(containerColor = CoinYellow, contentColor = ArcadeBlack)
                            ) {
                                Text("JUGAR", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
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
            colors = CardDefaults.cardColors(containerColor = LeatherBlack),
            border = BorderStroke(1.dp, ArcadeMagenta),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Perfil: ${profile?.username ?: "Invitado"}", color = CoinYellow, fontWeight = FontWeight.Bold)
                Text(
                    "Motor Rust v${engineStatus?.engineVersion ?: "?"} · ${engineStatus?.targetArch ?: "?"} · FBNeo: ${if (engineStatus?.fbneoReady == true) "LISTO" else "NO DETECTADO"}",
                    color = RetroIvory,
                    fontSize = 12.sp
                )
                Text(
                    "Caché local (ROMs/BIOS/States): ${"%.1f".format(cacheMb)} MB",
                    color = SteelGray,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshLogs, border = BorderStroke(1.dp, CoinYellow)) {
                        Text("PING", color = CoinYellow, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onClearCache, border = BorderStroke(1.dp, ArcadeMagenta)) {
                        Text("LIMPIAR CACHÉ", color = ArcadeMagenta, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onSignOut, border = BorderStroke(1.dp, ArcadeOrange)) {
                        Text("SALIR", color = ArcadeOrange, fontSize = 11.sp)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(DeepPurple, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        "LOGS INTERNOS (RUST):",
                        color = SteelGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                items(logs) { line ->
                    Text(
                        line,
                        color = RetroIvory,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
