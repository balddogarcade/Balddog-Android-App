package dog.bald.arcade.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    game: GameDto,
    onBack: () -> Unit,
    onPlayClick: (GameDto) -> Unit,
    onRankingClick: (GameDto) -> Unit,
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
