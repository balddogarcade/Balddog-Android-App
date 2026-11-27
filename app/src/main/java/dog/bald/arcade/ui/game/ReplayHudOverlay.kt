package dog.bald.arcade.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onSeekToFrame: (Long) -> Unit,
    onSelectSpeed: (Int) -> Unit,
    onCaptureScreenshot: () -> Unit,
    onExportVideoClip: () -> Unit,
    onExit: () -> Unit,
    onOpenMenu: (() -> Unit)? = null,
) {
    val numFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val totalFrames = replayHud.totalFrames.toLong().coerceAtLeast(1L)
    val currentFrame = replayHud.currentFrame.toLong().coerceIn(0L, totalFrames)
    val progress = (currentFrame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // =========================================================================
        // PILAR IZQUIERDO: Info de partida, marcador, tiempo, seekbar y velocidades
        // Anclado exclusivamente al lateral para no tapar el centro de juego (4:3)
        // =========================================================================
        Column(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(148.dp)
                    .wrapContentHeight()
                    .background(ObsidianBlack.copy(alpha = 0.88f))
                    .border(1.dp, SteelBorder, RectangleShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Badge de estado REPLAY / REPLAY FIN
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (replayHud.finished) "REPLAY FIN" else "REPLAY ${replayHud.speed}x",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureBlack,
                    modifier =
                        Modifier
                            .background(if (replayHud.finished) Color(0xFFFFD54F) else Color(0xFF4ADE80))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                if (replayHud.paused) {
                    Text(
                        text = "PAUSA",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite,
                        modifier =
                            Modifier
                                .background(CharcoalBorder)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            // Nombre del jugador
            Text(
                text = playerName.ifBlank { "Jugador" },
                color = PureWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Marcadores actual y objetivo
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "SCORE ${numFmt.format(currentScore)}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite,
                )
                Text(
                    text = "OBJ ${numFmt.format(targetScore)}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = SilverGray,
                )
            }

            // Indicador de tiempo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${formatFramesTime(currentFrame)} / ${formatFramesTime(totalFrames)}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = SilverGray,
                )
                if (replayHud.seeking) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = Color(0xFFFFD54F),
                    )
                    Text(
                        text = "Avanzando...",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = Color(0xFFFFD54F),
                    )
                }
            }

            // Barra de progreso interactiva (Seekbar táctil con adelantado manual)
            ReplaySeekBar(
                progress = progress,
                enabled = !replayHud.seeking,
                onSeek = { p ->
                    val frame = (p * totalFrames).toLong().coerceIn(0L, totalFrames)
                    onSeekToFrame(frame)
                },
            )

            // Botones de adelantado rápido (+10s, +30s)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                QuickSkipButton(
                    label = "+10s",
                    enabled = !replayHud.seeking,
                    onClick = {
                        val target = (currentFrame + 600L).coerceIn(0L, totalFrames)
                        onSeekToFrame(target)
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickSkipButton(
                    label = "+30s",
                    enabled = !replayHud.seeking,
                    onClick = {
                        val target = (currentFrame + 1800L).coerceIn(0L, totalFrames)
                        onSeekToFrame(target)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // Selector de velocidades 1x / 2x / 4x
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(1, 2, 4).forEach { spd ->
                    val selected = replayHud.speed.toInt() == spd
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .background(if (selected) PureWhite else CarbonSurface)
                                .border(1.dp, if (selected) PureWhite else CharcoalBorder, RectangleShape)
                                .clickable { onSelectSpeed(spd) }
                                .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "${spd}x",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) PureBlack else PureWhite,
                        )
                    }
                }
            }
        }

        // =========================================================================
        // PILAR DERECHO: Acciones de control vertical (Play/Pause, Menú, Salir, Media)
        // Anclado al lateral derecho para no obstaculizar el espacio del juego (4:3)
        // =========================================================================
        Column(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(62.dp)
                    .wrapContentHeight()
                    .background(ObsidianBlack.copy(alpha = 0.88f))
                    .border(1.dp, SteelBorder, RectangleShape)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Salir del replay
            ReplaySideButton(
                icon = Icons.Default.Close,
                label = "SALIR",
                onClick = onExit,
            )

            // Menú
            if (onOpenMenu != null) {
                ReplaySideButton(
                    icon = Icons.Default.Menu,
                    label = "MENÚ",
                    onClick = onOpenMenu,
                )
            }

            // Play / Pause
            ReplaySideButton(
                icon = if (replayHud.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                label = if (replayHud.paused) "PLAY" else "PAUSA",
                onClick = onTogglePause,
                highlight = replayHud.paused,
            )

            // Reiniciar replay (desde frame 0)
            ReplaySideButton(
                icon = Icons.Default.Replay,
                label = "INICIO",
                onClick = onRestart,
            )

            // Captura de pantalla PNG
            ReplaySideButton(
                icon = Icons.Default.CameraAlt,
                label = "PNG",
                onClick = onCaptureScreenshot,
            )

            // Exportar clip MP4
            ReplaySideButton(
                icon = Icons.Default.Videocam,
                label = if (exportProgressPct != null) "$exportProgressPct%" else "MP4",
                onClick = onExportVideoClip,
                enabled = exportProgressPct == null,
                iconTint = if (exportProgressPct != null) Color(0xFFFFD54F) else PureWhite,
            )
        }
    }
}

/**
 * Barra interactiva que permite tanto pinchar directamente en cualquier punto (tap)
 * como arrastrar (scrub) para adelantar o retroceder manualmente.
 */
@Composable
private fun ReplaySeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    val displayProgress = (draggingProgress ?: progress).coerceIn(0f, 1f)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek(fraction)
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            draggingProgress = fraction
                        },
                        onDragEnd = {
                            draggingProgress?.let { onSeek(it) }
                            draggingProgress = null
                        },
                        onDragCancel = {
                            draggingProgress = null
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            draggingProgress = fraction
                        },
                    )
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Pista de fondo
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(CharcoalBorder, RectangleShape),
        )
        // Progreso relleno
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction = displayProgress.coerceAtLeast(0.01f))
                    .height(6.dp)
                    .background(if (draggingProgress != null) Color(0xFF4ADE80) else PureWhite, RectangleShape),
        )
        // Scrubber / Cabezal indicador táctil
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction = displayProgress.coerceAtLeast(0.01f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .background(PureWhite, RectangleShape)
                        .border(1.dp, PureBlack, RectangleShape),
            )
        }
    }
}

@Composable
private fun QuickSkipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .background(CarbonSurface)
                .border(1.dp, CharcoalBorder, RectangleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.FastForward,
                contentDescription = null,
                tint = SilverGray,
                modifier = Modifier.size(11.dp),
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = PureWhite,
            )
        }
    }
}

@Composable
private fun ReplaySideButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    iconTint: Color = PureWhite,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (highlight) PureWhite else CarbonSurface)
                .border(1.dp, if (highlight) PureWhite else CharcoalBorder, RectangleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (highlight) PureBlack else iconTint,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) PureBlack else PureWhite,
        )
    }
}

private fun formatFramesTime(frames: Long): String {
    val totalSecs = (frames / 60L).coerceAtLeast(0L)
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
