package dog.bald.arcade.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.data.GameRankingSummaryDto
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.GraphiteSurface
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArcadeGameCard(
    game: GameDto,
    isDownloaded: Boolean,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    busy: Boolean = false,
    onPrimaryAction: () -> Unit,
    onLeaderboardClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    rankingSummary: GameRankingSummaryDto? = null,
    modifier: Modifier = Modifier
) {
    val cardShape = RectangleShape

    Card(
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        border = BorderStroke(1.dp, CharcoalBorder),
        shape = cardShape,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Full-height 3:4 poster stage with uncropped ContentScale.Fit
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(ObsidianBlack)
            ) {
                GameCoverImage(
                    slug = game.slug,
                    contentDescription = game.title,
                    contentScale = ContentScale.Fit,
                    shape = RectangleShape,
                    backgroundColor = ObsidianBlack,
                    showBorder = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    ArcadeBadge(
                        text = game.system.uppercase(),
                        style = ArcadeBadgeStyle.Overlay
                    )
                    OverlayStatPill(
                        icon = Icons.Default.Download,
                        contentDescription = "Descargas",
                        text = "${game.downloadCount}"
                    )
                    if (game.totalPlaySeconds > 0L) {
                        OverlayStatPill(
                            icon = Icons.Default.Schedule,
                            contentDescription = "Tiempo jugado",
                            text = formatCompactDuration(game.totalPlaySeconds)
                        )
                    }
                }

                if (onLeaderboardClick != null || onDeleteClick != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        if (onLeaderboardClick != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RectangleShape)
                                    .background(ObsidianBlack.copy(alpha = 0.84f))
                                    .border(1.dp, CharcoalBorder, RectangleShape)
                                    .clickable(onClick = onLeaderboardClick)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EmojiEvents,
                                    contentDescription = "Ver ranking",
                                    tint = PureWhite,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (onDeleteClick != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RectangleShape)
                                    .background(ObsidianBlack.copy(alpha = 0.84f))
                                    .border(1.dp, CharcoalBorder, RectangleShape)
                                    .clickable(onClick = onDeleteClick)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Eliminar ROM",
                                    tint = SilverGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                val top1 = rankingSummary?.top3?.firstOrNull()
                val myRank = rankingSummary?.myRank
                if (top1 != null || myRank != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    ) {
                        if (top1 != null) {
                            Box(
                                modifier = Modifier
                                    .background(ObsidianBlack.copy(alpha = 0.88f), RectangleShape)
                                    .border(1.dp, SteelBorder, RectangleShape)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "#1 ${top1.username}",
                                    color = PureWhite,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (myRank != null) {
                            Box(
                                modifier = Modifier
                                    .background(PureWhite, RectangleShape)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "TÚ #$myRank",
                                    color = PureBlack,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = CharcoalBorder)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = PureWhite,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ArcadeBadge(
                        text = "${game.maxPlayers}P",
                        style = ArcadeBadgeStyle.Outline
                    )
                    if (game.isCoop) {
                        ArcadeBadge(
                            text = "CO-OP",
                            style = ArcadeBadgeStyle.Solid
                        )
                    }
                    if (game.sizeBytes > 0L) {
                        val sizeMb = (game.sizeBytes / (1024.0 * 1024.0)).roundToInt().coerceAtLeast(1)
                        ArcadeBadge(
                            text = "$sizeMb MB",
                            style = ArcadeBadgeStyle.Outline
                        )
                    }
                }

                if (isDownloading) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                color = PureWhite,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp)
                            )
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                color = PureWhite,
                                trackColor = GraphiteSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RectangleShape)
                            )
                        }
                    }
                } else {
                    Button(
                        enabled = !busy,
                        onClick = onPrimaryAction,
                        shape = RectangleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDownloaded) PureWhite else GraphiteSurface,
                            contentColor = if (isDownloaded) PureBlack else PureWhite,
                            disabledContainerColor = GraphiteSurface.copy(alpha = 0.5f),
                            disabledContentColor = SilverGray.copy(alpha = 0.5f)
                        ),
                        border = if (isDownloaded) null else BorderStroke(1.dp, SteelBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Default.PlayArrow else Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isDownloaded) "JUGAR" else "DESCARGAR",
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayStatPill(
    icon: ImageVector,
    contentDescription: String,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RectangleShape)
            .background(ObsidianBlack.copy(alpha = 0.84f))
            .border(1.dp, SteelBorder, RectangleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = PureWhite,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = text,
            color = PureWhite,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
            maxLines = 1
        )
    }
}

private fun formatCompactDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m"
        else -> "<1m"
    }
}
