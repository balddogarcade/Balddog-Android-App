package dog.bald.arcade.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dog.bald.arcade.data.GameDto

@Composable
fun GameDetailHistoryCard(
    game: GameDto,
    onWatchTrailer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasHistory = !game.history.isNullOrBlank()
    val videoId = game.youtubeVideoId?.takeIf { it.isNotBlank() }
    if (!hasHistory && videoId == null) return

    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (videoId != null) {
            InlineYouTubePlayer(
                videoId = videoId,
                onOpenExternal = { onWatchTrailer(videoId) },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (hasHistory) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Historia",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = game.history.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (game.history.orEmpty().length > 260) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (expanded) "Ver menos" else "Leer más",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { expanded = !expanded },
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InlineYouTubePlayer(
    videoId: String,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    val webView =
        remember(videoId) {
            WebView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()

                val safeId = videoId.replace(Regex("[^A-Za-z0-9_-]"), "")
                val html =
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                        <style>
                            html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
                            iframe { width: 100%; height: 100%; border: 0; }
                        </style>
                    </head>
                    <body>
                        <iframe
                            src="https://www.youtube-nocookie.com/embed/$safeId?rel=0&playsinline=1"
                            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                            allowfullscreen>
                        </iframe>
                    </body>
                    </html>
                    """.trimIndent()

                loadDataWithBaseURL(
                    "https://www.youtube-nocookie.com",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }

    DisposableEffect(webView) {
        onDispose {
            webView.onPause()
            webView.destroy()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Gameplay",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenExternal,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Abrir en YouTube")
        }
    }
}
