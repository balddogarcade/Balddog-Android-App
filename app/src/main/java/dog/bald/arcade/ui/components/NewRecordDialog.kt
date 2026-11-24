package dog.bald.arcade.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.MyHighScoreDto
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import java.text.NumberFormat
import java.util.Locale

data class PostMatchRecordBannerData(
    val game: GameDto,
    val score: Long,
    val previousRank: Long?,
    val newRank: Long?
)

internal fun detectImprovedHighScore(
    prevScoresByGameId: Map<String, MyHighScoreDto>,
    updatedScores: List<MyHighScoreDto>
): MyHighScoreDto? {
    return updatedScores.firstOrNull { curr ->
        val prev = prevScoresByGameId[curr.gameId]
        prev == null || curr.score > prev.score || curr.rank < prev.rank
    }
}

@Composable
fun NewRecordDialog(
    data: PostMatchRecordBannerData,
    onDismiss: () -> Unit,
    onViewRanking: (GameDto) -> Unit
) {
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val rankJumpText = when {
        data.previousRank != null && data.newRank != null && data.newRank < data.previousRank ->
            "SUBISTE DE #${data.previousRank} A #${data.newRank}"
        data.newRank != null ->
            "PUESTO ACTUAL: #${data.newRank}"
        else ->
            "¡NUEVO RÉCORD PERSONAL 1CC!"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        shape = RectangleShape,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "NUEVO RÉCORD 1CC",
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = data.game.title,
                    color = SilverGray,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = numberFmt.format(data.score),
                        color = PureWhite,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Text(
                        text = rankJumpText,
                        color = PureWhite,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Puntaje validado con 1 crédito (Hardcore).",
                        color = MutedGray,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onViewRanking(data.game) },
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PureWhite,
                    contentColor = PureBlack
                )
            ) {
                Text(
                    text = "VER RANKING",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CERRAR", color = SilverGray)
            }
        }
    )
}
