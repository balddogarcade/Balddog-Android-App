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
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SteelBorder

@Composable
fun InGameQuickMenu(
    gameTitle: String,
    fps: Float,
    statusMessage: String?,
    hasPhysicalController: Boolean,
    touchConfig: TouchLayoutConfig,
    editLayoutMode: Boolean,
    isNetplay: Boolean = false,
    voiceVolume: Float = 1.0f,
    onVoiceVolumeChange: (Float) -> Unit = {},
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
            .background(ObsidianBlack.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(320.dp)
                .fillMaxHeight()
                .background(CarbonSurface.copy(alpha = 0.96f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = gameTitle.ifBlank { "Bald Dog" },
                color = PureWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "FPS: ${"%.1f".format(fps)} · ${if (hasPhysicalController) "Mando BT conectado" else "Modo Táctil"}",
                color = MutedGray,
                fontSize = 12.sp
            )

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    color = SilverGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onSaveState,
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteSurface, contentColor = PureWhite),
                    border = BorderStroke(1.dp, SteelBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SAVE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = onLoadState,
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteSurface, contentColor = PureWhite),
                    border = BorderStroke(1.dp, SteelBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("LOAD", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Text("Opacidad Táctil (${(touchConfig.opacity * 100).toInt()}%)", color = PureWhite, fontSize = 12.sp)
            Slider(
                value = touchConfig.opacity,
                onValueChange = { onTouchConfigChange(touchConfig.copy(opacity = it)) },
                valueRange = 0.2f..1.0f,
                colors = SliderDefaults.colors(thumbColor = PureWhite, activeTrackColor = SilverGray, inactiveTrackColor = GraphiteSurface)
            )

            Text("Escala Botones (${"%.2f".format(touchConfig.scale)}x)", color = PureWhite, fontSize = 12.sp)
            Slider(
                value = touchConfig.scale,
                onValueChange = { onTouchConfigChange(touchConfig.copy(scale = it)) },
                valueRange = 0.75f..1.4f,
                colors = SliderDefaults.colors(thumbColor = PureWhite, activeTrackColor = SilverGray, inactiveTrackColor = GraphiteSurface)
            )

            if (isNetplay) {
                Text("Volumen Voz (${(voiceVolume * 100).toInt()}%)", color = PureWhite, fontSize = 12.sp)
                Slider(
                    value = voiceVolume,
                    onValueChange = onVoiceVolumeChange,
                    valueRange = 0.0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = PureWhite, activeTrackColor = SilverGray, inactiveTrackColor = GraphiteSurface)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mostrar táctil con mando", color = PureWhite, fontSize = 12.sp)
                Switch(
                    checked = touchConfig.forceShowTouch,
                    onCheckedChange = { onTouchConfigChange(touchConfig.copy(forceShowTouch = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureBlack,
                        checkedTrackColor = PureWhite,
                        uncheckedThumbColor = MutedGray,
                        uncheckedTrackColor = GraphiteSurface
                    )
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onToggleEditLayout,
                    border = BorderStroke(1.dp, if (editLayoutMode) PureWhite else SteelBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (editLayoutMode) "LISTO" else "REUBICAR",
                        color = PureWhite,
                        fontSize = 11.sp
                    )
                }
                OutlinedButton(
                    onClick = onResetLayout,
                    border = BorderStroke(1.dp, CharcoalBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("RESET", color = MutedGray, fontSize = 11.sp)
                }
            }

            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("REANUDAR", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onExit,
                border = BorderStroke(1.dp, SteelBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SALIR DE PARTIDA", color = SilverGray, fontWeight = FontWeight.Bold)
            }
        }
    }
}
