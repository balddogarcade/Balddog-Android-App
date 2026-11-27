package dog.bald.arcade.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dog.bald.arcade.data.GameDto
import dog.bald.arcade.diagnostics.AnalyticsHelper
import dog.bald.arcade.diagnostics.CrashlyticsHelper
import dog.bald.arcade.diagnostics.PerformanceHelper
import dog.bald.arcade.ui.components.GameDetailGallery
import dog.bald.arcade.ui.components.GameDetailHeader
import dog.bald.arcade.ui.components.GameDetailHistoryCard
import dog.bald.arcade.ui.components.GameDetailSpecsCard
import dog.bald.arcade.ui.components.SkeletonBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    game: GameDto,
    onBack: () -> Unit,
    onPlayClick: (GameDto) -> Unit,
    onRankingClick: (GameDto) -> Unit,
    onQuickMatchClick: ((GameDto) -> Unit)? = null,
    isLoading: Boolean = false,
) {
    val context = LocalContext.current

    LaunchedEffect(game.id) {
        CrashlyticsHelper.setCurrentGameDetail(game.id, game.slug)
        AnalyticsHelper.logGameDetailViewed(game.id, game.slug)
    }

    DisposableEffect(game.id) {
        val trace = PerformanceHelper.startTrace("game_detail_view_trace")
        onDispose {
            PerformanceHelper.stopTrace(trace, mapOf("rom_slug" to game.slug))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = game.shortTitle ?: game.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        if (isLoading) {
            GameDetailSkeleton(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GameDetailHeader(
                    game = game,
                    onPlayClick = { onPlayClick(game) },
                    onRankingClick = { onRankingClick(game) },
                )
                if (onQuickMatchClick != null) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onQuickMatchClick(game) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("BUSCAR PARTIDA RÁPIDA (ONLINE)")
                    }
                }
                GameDetailGallery(
                    game = game,
                    onImageOpened = { imageType ->
                        AnalyticsHelper.logGameGalleryOpened(game.slug, imageType)
                    },
                )
                GameDetailSpecsCard(game = game)
                GameDetailHistoryCard(
                    game = game,
                    onWatchTrailer = { videoId ->
                        AnalyticsHelper.logGameTrailerOpened(game.slug)
                        runCatching {
                            val intent =
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.youtube.com/watch?v=$videoId"),
                                )
                            context.startActivity(intent)
                        }.onFailure { err ->
                            CrashlyticsHelper.logGameDetailError(game.slug, err)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GameDetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SkeletonBox(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(84.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SkeletonBox(
                modifier = Modifier.size(width = 104.dp, height = 136.dp),
                shape = RoundedCornerShape(12.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBox(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.85f)
                            .height(24.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                SkeletonBox(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.6f)
                            .height(16.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                    )
                    SkeletonBox(
                        modifier =
                            Modifier
                                .width(100.dp)
                                .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                SkeletonBox(
                    modifier = Modifier.size(width = 160.dp, height = 112.dp),
                    shape = RoundedCornerShape(10.dp),
                )
            }
        }
        SkeletonBox(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            shape = RoundedCornerShape(12.dp),
        )
        SkeletonBox(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            shape = RoundedCornerShape(12.dp),
        )
    }
}
