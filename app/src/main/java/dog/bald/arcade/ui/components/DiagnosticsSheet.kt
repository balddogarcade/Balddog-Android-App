package dog.bald.arcade.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.DangerRed
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.ReadyGreenAccent
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.delay
import uniffi.balddog_android.EngineStatus

/**
 * Hoja modal bajo demanda para diagnóstico del motor Rust/FBNeo y visor de logs internos.
 * Mantiene los logs técnicos fuera de la vista principal del perfil.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    engineStatus: EngineStatus?,
    logs: List<String>,
    onRefreshLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CarbonSurface,
        contentColor = PureWhite,
        shape = RectangleShape,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cabecera
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = "DIAGNÓSTICO DEL MOTOR",
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite,
                        )
                        Text(
                            text = "${logs.size} eventos en búfer",
                            color = MutedGray,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFamily,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val header = "Rust v${engineStatus?.engineVersion ?: "?"} | ${engineStatus?.targetArch ?: "?"} | FBNeo=${engineStatus?.fbneoReady == true}"
                            val payload =
                                buildString {
                                    appendLine(header)
                                    appendLine("---")
                                    logs.forEach { appendLine(it) }
                                }
                            clipboard.setText(AnnotatedString(payload))
                            copied = true
                        },
                        enabled = logs.isNotEmpty(),
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, CharcoalBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copiar logs",
                            tint = if (copied) ReadyGreenAccent else SilverGray,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (copied) "COPIADO" else "COPIAR",
                            color = if (copied) ReadyGreenAccent else SilverGray,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFamily,
                        )
                    }

                    OutlinedButton(
                        onClick = onRefreshLogs,
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, SteelBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Ping al motor",
                            tint = PureWhite,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PING",
                            color = PureWhite,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFamily,
                        )
                    }
                }
            }

            // Resumen técnico del motor
            val fbneoReady = engineStatus?.fbneoReady == true
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(GraphiteSurface, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "balddog-core v${engineStatus?.engineVersion ?: "?"} · ${engineStatus?.targetArch ?: "?"}",
                        color = PureWhite,
                        fontSize = 12.sp,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (fbneoReady) "Core FBNeo verificado y listo" else "Core FBNeo no detectado",
                        color = MutedGray,
                        fontSize = 11.sp,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (fbneoReady) ReadyGreenAccent else DangerRed),
                    )
                    Text(
                        text = if (fbneoReady) "LISTO" else "ERROR",
                        color = if (fbneoReady) ReadyGreenAccent else DangerRed,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            HorizontalDivider(color = CharcoalBorder)

            // Consola de logs
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp)
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape)
                        .padding(12.dp),
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Sin eventos registrados aún. Pulsa PING para verificar el motor.",
                        color = MutedGray,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                color = SilverGray,
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMonoFamily,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
