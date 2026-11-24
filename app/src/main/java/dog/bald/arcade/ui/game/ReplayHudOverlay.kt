package dog.bald.arcade.ui.game

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder
import uniffi.balddog_android.ReplayHudInfo
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ReplayHudOverlay(
    playerName: String,
    targetScore: Long,
    currentScore: Long,
    replayHud: ReplayHudInfo,
    exportProgressPct: Int?,
    onTogglePause: () -> Unit,
    onRestart: () -> Unit,
    onSelectSpeed: (Int) -> Unit,
    onCaptureScreenshot: () -> Unit,
    onExportVideoClip: () -> Unit,
    onExit: () -> Unit,
    onOpenMenu: (() -> Unit)? = null
) {
    val numFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val totalFrames = replayHud.totalFrames.toLong().coerceAtLeast(1L)
    val currentFrame = replayHud.currentFrame.toLong().coerceIn(0L, totalFrames)
    val progress = currentFrame.toFloat() / totalFrames.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Barra superior: Badge REPLAY, jugador, puntaje y botón salir
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(ObsidianBlack.copy(alpha = 0.88f))
                .border(1.dp, SteelBorder, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (replayHud.finished) "REPLAY FIN" else "REPLAY ${replayHud.speed}x",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureBlack,
                    modifier = Modifier
                        .background(if (replayHud.finished) Color(0xFFFFD54F) else Color(0xFF4ADE80))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = playerName.ifBlank { "Jugador" },
                        color = PureWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "SCORE ${numFmt.format(currentScore)} / ${numFmt.format(targetScore)}",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = SilverGray
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (onOpenMenu != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(CarbonSurface)
                            .border(1.dp, CharcoalBorder, RectangleShape)
                            .clickable(onClick = onOpenMenu)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Abrir menú del replay",
                            tint = PureWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "MENÚ",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(CarbonSurface)
                        .border(1.dp, CharcoalBorder, RectangleShape)
                        .clickable(onClick = onExit)
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Salir del replay",
                        tint = PureWhite,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "SALIR",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite
                    )
                }
            }
        }

        // Barra inferior flotante: Controles de reproducción, velocidades, captura y clip MP4
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(ObsidianBlack.copy(alpha = 0.90f))
                .border(1.dp, SteelBorder, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = PureWhite,
                    trackColor = CharcoalBorder,
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${formatFramesTime(currentFrame)} / ${formatFramesTime(totalFrames)}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = SilverGray
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Play / Pause
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .background(PureWhite)
                            .clickable(onClick = onTogglePause)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (replayHud.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (replayHud.paused) "Reanudar" else "Pausar",
                            tint = PureBlack,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Restart
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .background(CarbonSurface)
                            .border(1.dp, CharcoalBorder, RectangleShape)
                            .clickable(onClick = onRestart)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Reiniciar replay",
                            tint = PureWhite,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Velocidades 1x / 2x / 4x
                    listOf(1, 2, 4).forEach { spd ->
                        val selected = replayHud.speed.toInt() == spd
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .background(if (selected) PureWhite else CarbonSurface)
                                .border(1.dp, if (selected) PureWhite else CharcoalBorder, RectangleShape)
                                .clickable { onSelectSpeed(spd) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${spd}x",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) PureBlack else PureWhite
                            )
                        }
                    }
                }

                // Acciones de medios: Captura PNG y Clip MP4
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(CarbonSurface)
                            .border(1.dp, CharcoalBorder, RectangleShape)
                            .clickable(onClick = onCaptureScreenshot)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Captura de pantalla",
                            tint = PureWhite,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PNG",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(CarbonSurface)
                            .border(1.dp, CharcoalBorder, RectangleShape)
                            .clickable(enabled = exportProgressPct == null, onClick = onExportVideoClip)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Exportar clip MP4",
                            tint = if (exportProgressPct != null) Color(0xFFFFD54F) else PureWhite,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (exportProgressPct != null) "${exportProgressPct}%" else "MP4",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (exportProgressPct != null) Color(0xFFFFD54F) else PureWhite
                        )
                    }
                }
            }
        }
    }
}

private fun formatFramesTime(frames: Long): String {
    val totalSecs = (frames / 60L).coerceAtLeast(0L)
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
