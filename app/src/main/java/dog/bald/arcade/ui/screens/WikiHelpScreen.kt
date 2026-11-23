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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private data class WikiSection(
    val id: String,
    val title: String,
    val summary: String,
    val bullets: List<String>
)

private val wikiSections = listOf(
    WikiSection(
        id = "catalog",
        title = "1. CATÁLOGO, ROMs IPFS Y PRIMEROS PASOS",
        summary = "Cómo descargar juegos, BIOS y empezar a jugar en segundos.",
        bullets = listOf(
            "En la pestaña Catálogo verás todos los juegos curados con soporte de sistema (CPS-1, CPS-2, Neo Geo, Konami, etc.).",
            "Toca DESCARGAR (o JUGAR directo desde Rankings) para bajar la ROM y su BIOS requerida desde IPFS con verificación SHA-256.",
            "Puedes liberar espacio en cualquier momento tocando el ícono de papelera en la tarjeta del juego o desde Perfil → Limpiar Caché."
        )
    ),
    WikiSection(
        id = "rankings",
        title = "2. RANKINGS, REGLAS 1CC Y REPLAYS VERIFICADOS",
        summary = "Cómo entrar al leaderboard oficial con partidas de 1 crédito.",
        bullets = listOf(
            "Solo las partidas en solitario con 1 crédito (1CC) y modo Hardcore (sin Save States ni trucos) envían puntaje al ranking oficial.",
            "Al finalizar tu partida se guarda automáticamente un replay determinista que puede ser auditado y reproducido por otros jugadores.",
            "En la vista individual de cada juego puedes alternar entre Top 1CC y Tiempo Jugado, y filtrar por TODOS o SIGUIENDO."
        )
    ),
    WikiSection(
        id = "gamification",
        title = "3. PUNTOS GLOBALES, RANGOS Y MEDALLAS",
        summary = "Fórmula del Ranking Global, desempates y progresión.",
        bullets = listOf(
            "Puntos Globales por cada juego con ranking activo: #1 = 100 pts, #2 = 75 pts, #3 = 50 pts, #4–#10 = 25 pts, 1CC (#11+) = 10 pts.",
            "Desempate Global: a igualdad de puntos gana quien tenga menor tiempo total acumulado. En un juego: menor tiempo en ese juego, luego fecha más antigua.",
            "Rangos Arcade: Novato (0+) → Retador (250+) → Luchador (600+) → Maestro (1,200+) → Gran Maestro (2,500+) → Leyenda (5,000+).",
            "Medallas: Rey del Arcade (#1), Podio (Top 3), 1CC Master, Replay Verificado, Veterano (25h/100h/250h) y Anfitrión Netplay (25/100/250 salas)."
        )
    ),
    WikiSection(
        id = "netplay",
        title = "4. SALAS NETPLAY (ONLINE / LAN) Y VOZ",
        summary = "Juega cooperativo o arcade en línea y red local.",
        bullets = listOf(
            "En la pestaña Salas puedes crear una sala Online o LAN, o unirte a una existente con su código.",
            "Las salas creadas por jugadores que sigues muestran la etiqueta [SIGUIENDO] y puedes filtrarlas arriba.",
            "Dentro del lobby puedes tocar cualquier jugador ocupado o mensaje del chat para ver su Perfil Público y seguirlo sin salir de la sala."
        )
    ),
    WikiSection(
        id = "profile",
        title = "5. CONTROLES, PERFIL PÚBLICO Y PRIVACIDAD",
        summary = "Personalización, redes sociales, seguidores y gamepad.",
        bullets = listOf(
            "Usa botones táctiles configurables en pantalla o conecta cualquier Gamepad Bluetooth/USB (se detecta automáticamente).",
            "En Perfil → EDITAR PERFIL puedes cambiar tu username, bio, país, generar un avatar pixel-art aleatorio y agregar tus redes sociales (URLs https:// o tag de Discord).",
            "El toggle 'Perfil privado' oculta tus redes, tiempo total e historial reciente en tu perfil público, manteniendo visibles tus récords 1CC y medallas."
        )
    )
)

@Composable
fun WikiHelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var searchQuery by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(wikiSections.first().id) }

    val filtered = remember(searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) wikiSections
        else wikiSections.filter { sec ->
            sec.title.contains(q, ignoreCase = true) ||
                sec.summary.contains(q, ignoreCase = true) ||
                sec.bullets.any { it.contains(q, ignoreCase = true) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureBlack)
                .border(1.dp, CharcoalBorder, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(CarbonSurface, RectangleShape)
                    .border(1.dp, SteelBorder, RectangleShape)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "VOLVER",
                        color = PureWhite,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                text = "WIKI / AYUDA",
                color = PureWhite,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            placeholder = { Text("Buscar guía (1CC, puntos, netplay, perfil...)", color = MutedGray, fontSize = 12.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MutedGray,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = PureWhite,
                unfocusedTextColor = PureWhite,
                focusedBorderColor = PureWhite,
                unfocusedBorderColor = CharcoalBorder,
                cursorColor = PureWhite
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.id }) { section ->
                val isExpanded = expandedId == section.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CarbonSurface, RectangleShape)
                        .border(1.dp, if (isExpanded) SteelBorder else CharcoalBorder, RectangleShape)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedId = if (isExpanded) null else section.id
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = section.title,
                                color = PureWhite,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = section.summary,
                                color = MutedGray,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = PureWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (isExpanded) {
                        HorizontalDivider(color = CharcoalBorder)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ObsidianBlack)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            section.bullets.forEach { bullet ->
                                Text(
                                    text = "• $bullet",
                                    color = SilverGray,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
