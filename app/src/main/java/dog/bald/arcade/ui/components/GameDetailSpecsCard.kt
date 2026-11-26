package dog.bald.arcade.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dog.bald.arcade.data.GameDto

@Composable
fun GameDetailSpecsCard(
    game: GameDto,
    modifier: Modifier = Modifier,
) {
    val rows =
        listOfNotNull(
            game.genre?.takeIf { it.isNotBlank() }?.let { "Género" to it },
            game.nplayers?.takeIf { it.isNotBlank() }?.let { "Jugadores" to it }
                ?: game.players?.let { "Jugadores" to "$it" },
            game.inputControls?.takeIf { it.isNotBlank() }?.let { "Controles" to it },
            game.inputButtons?.let { "Botones" to "$it" },
            game.emulatorName?.takeIf { it.isNotBlank() }?.let { "Referencia MAME" to it },
            game.gameName?.takeIf { it.isNotBlank() }?.let { "ROM" to it },
        )

    if (rows.isEmpty()) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Ficha técnica",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            rows.forEach { (label, value) ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
