package dog.bald.arcade.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.R
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.InterFamily
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray

/**
 * Full-screen preload overlay shown while the app restores session or loads initial data.
 */
@Composable
fun AppPreloadScreen(message: String = "Cargando...") {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ObsidianBlack)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_text),
            contentDescription = "Bald Dog",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .height(42.dp),
        )

        Row(
            modifier = Modifier.align(Alignment.BottomEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                color = SilverGray,
                fontFamily = InterFamily,
                fontSize = 13.sp,
            )
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = PureWhite,
                trackColor = CharcoalBorder,
                strokeWidth = 2.dp,
            )
        }
    }
}
