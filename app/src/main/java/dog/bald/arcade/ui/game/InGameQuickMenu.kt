package dog.bald.arcade.ui.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.input.TouchLayoutConfig
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeMagenta
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.CoinYellow
import dog.bald.arcade.ui.theme.LeatherBlack
import dog.bald.arcade.ui.theme.RetroIvory
import dog.bald.arcade.ui.theme.SteelGray

@Composable
fun InGameQuickMenu(
    gameTitle: String,
    fps: Float,
    statusMessage: String?,
    hasPhysicalController: Boolean,
    touchConfig: TouchLayoutConfig,
    editLayoutMode: Boolean,
    onTouchConfigChange: (TouchLayoutConfig) -> Unit,
    onToggleEditLayout: () -> Unit,
    onResetLayout: () -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onResume: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArcadeBlack.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(320.dp)
                .fillMaxHeight()
                .background(LeatherBlack.copy(alpha = 0.94f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = gameTitle.ifBlank { "bald.dog ARCADE" },
                color = CoinYellow,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "FPS: ${"%.1f".format(fps)} · ${if (hasPhysicalController) "Mando BT conectado" else "Modo Táctil"}",
                color = SteelGray,
                fontSize = 12.sp
            )

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    color = ArcadeOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onSaveState,
                    colors = ButtonDefaults.buttonColors(containerColor = ArcadeMagenta, contentColor = RetroIvory),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SAVE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = onLoadState,
                    colors = ButtonDefaults.buttonColors(containerColor = ArcadeOrange, contentColor = ArcadeBlack),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("LOAD", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Text("Opacidad Táctil (${(touchConfig.opacity * 100).toInt()}%)", color = RetroIvory, fontSize = 12.sp)
            Slider(
                value = touchConfig.opacity,
                onValueChange = { onTouchConfigChange(touchConfig.copy(opacity = it)) },
                valueRange = 0.2f..1.0f,
                colors = SliderDefaults.colors(thumbColor = CoinYellow, activeTrackColor = ArcadeMagenta)
            )

            Text("Escala Botones (${"%.2f".format(touchConfig.scale)}x)", color = RetroIvory, fontSize = 12.sp)
            Slider(
                value = touchConfig.scale,
                onValueChange = { onTouchConfigChange(touchConfig.copy(scale = it)) },
                valueRange = 0.75f..1.4f,
                colors = SliderDefaults.colors(thumbColor = CoinYellow, activeTrackColor = ArcadeMagenta)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mostrar táctil con mando", color = RetroIvory, fontSize = 12.sp)
                Switch(
                    checked = touchConfig.forceShowTouch,
                    onCheckedChange = { onTouchConfigChange(touchConfig.copy(forceShowTouch = it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = ArcadeBlack, checkedTrackColor = CoinYellow)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onToggleEditLayout,
                    border = BorderStroke(1.dp, if (editLayoutMode) CoinYellow else ArcadeMagenta),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (editLayoutMode) "LISTO" else "REUBICAR",
                        color = RetroIvory,
                        fontSize = 11.sp
                    )
                }
                OutlinedButton(
                    onClick = onResetLayout,
                    border = BorderStroke(1.dp, SteelGray),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("RESET", color = SteelGray, fontSize = 11.sp)
                }
            }

            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = CoinYellow, contentColor = ArcadeBlack),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("REANUDAR", fontWeight = FontWeight.ExtraBold)
            }

            OutlinedButton(
                onClick = onExit,
                border = BorderStroke(1.dp, ArcadeOrange),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SALIR DE PARTIDA", color = ArcadeOrange, fontWeight = FontWeight.Bold)
            }
        }
    }
}
