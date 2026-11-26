package dog.bald.arcade.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily

@Composable
fun RankingsMechanicsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        shape = RectangleShape,
        title = {
            Text(
                text = "MECÁNICAS DE RANKING",
                color = PureWhite,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MechanicsBlock(
                    title = "PUNTOS GLOBALES (POR JUEGO 1CC)",
                    lines =
                        listOf(
                            "#1 Oro: 100 pts · #2 Plata: 75 pts · #3 Bronce: 50 pts",
                            "#4–#10 Top 10: 25 pts · #11+ (1CC validado): 10 pts",
                            "Suman todos los juegos con ranking activo sin límite.",
                        ),
                )
                MechanicsBlock(
                    title = "DESEMPATES",
                    lines =
                        listOf(
                            "Global: menor tiempo total acumulado gana el empate.",
                            "Por juego: menor tiempo acumulado en ese juego; luego quien lo logró primero.",
                        ),
                )
                MechanicsBlock(
                    title = "RANGOS ARCADE",
                    lines =
                        listOf(
                            "Novato (0+) → Retador (150+) → Luchador (450+)",
                            "Maestro (900+) → Gran Maestro (1,500+) → Leyenda (2,000+)",
                        ),
                )
                MechanicsBlock(
                    title = "MEDALLAS",
                    lines =
                        listOf(
                            "Rey del Arcade (#1 en ≥1 juego) · Podio (Top 3)",
                            "1CC Master (≥1 récord 1CC) · Replay Verificado",
                            "Veterano: 25h / 100h / 250h jugadas",
                            "Anfitrión Netplay: 25 / 100 / 250 partidas en sala",
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "ENTENDIDO",
                    color = PureWhite,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

@Composable
private fun MechanicsBlock(
    title: String,
    lines: List<String>,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(ObsidianBlack, RectangleShape)
                .border(1.dp, CharcoalBorder, RectangleShape)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = PureWhite,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        HorizontalDivider(color = CharcoalBorder)
        lines.forEach { line ->
            Text(
                text = line,
                color = SilverGray,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
