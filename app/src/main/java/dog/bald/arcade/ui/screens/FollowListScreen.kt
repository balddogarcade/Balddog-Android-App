package dog.bald.arcade.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRank
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.CountryCatalog
import dog.bald.arcade.data.FollowUserDto
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.ui.components.DiceBearPixelArtAvatar
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
import kotlinx.coroutines.launch

@Composable
fun FollowListScreen(
    targetUserId: String,
    direction: String,
    targetUsername: String,
    repo: ArcadeRepository,
    onBack: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
    onFollowChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val myUserId = remember { repo.currentUserId() }
    var loading by remember(targetUserId, direction) { mutableStateOf(true) }
    var errorMsg by remember(targetUserId, direction) { mutableStateOf<String?>(null) }
    var users by remember(targetUserId, direction) { mutableStateOf<List<FollowUserDto>>(emptyList()) }

    LaunchedEffect(targetUserId, direction) {
        loading = true
        errorMsg = null
        runCatching { repo.fetchFollowList(targetUserId, direction, limit = 100, offset = 0) }
            .onSuccess { users = it }
            .onFailure { errorMsg = it.toUserMessage("No se pudo cargar la lista") }
        loading = false
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(ObsidianBlack)
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(PureBlack)
                    .border(1.dp, CharcoalBorder, RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .background(CarbonSurface, RectangleShape)
                        .border(1.dp, SteelBorder, RectangleShape)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "VOLVER",
                        color = PureWhite,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
            }
            Text(
                text = if (direction == "following") "SIGUIENDO · $targetUsername" else "SEGUIDORES · $targetUsername",
                color = PureWhite,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PureWhite)
                }
            }
            errorMsg != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(text = errorMsg!!, color = SilverGray, fontSize = 12.sp)
                }
            }
            users.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (direction == "following") "Aún no sigue a ningún jugador." else "Aún no tiene seguidores.",
                        color = MutedGray,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(users, key = { it.userId }) { user ->
                        val flag = CountryCatalog.flagEmoji(user.countryCode)
                        val rank = ArcadeRank.fromPoints(user.globalPoints)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(CarbonSurface, RectangleShape)
                                    .border(1.dp, CharcoalBorder, RectangleShape)
                                    .clickable { onOpenUserProfile(user.userId) }
                                    .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .background(ObsidianBlack, RectangleShape)
                                        .border(1.dp, SteelBorder, RectangleShape)
                                        .padding(3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                DiceBearPixelArtAvatar(
                                    seed = user.avatarSeed?.takeIf { it.isNotBlank() } ?: user.username,
                                    modifier = Modifier.size(34.dp),
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (flag.isNotEmpty()) {
                                        Text(text = flag, fontSize = 13.sp)
                                    }
                                    Text(
                                        text = user.username,
                                        color = PureWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "${rank.title} · ${user.globalPoints} PTS",
                                    color = MutedGray,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                )
                            }

                            if (user.userId != myUserId) {
                                val following = user.isFollowingByMe
                                val toggleFollow: () -> Unit = {
                                    val prev = users
                                    users =
                                        users.map {
                                            if (it.userId == user.userId) it.copy(isFollowingByMe = !following) else it
                                        }
                                    scope.launch {
                                        val res =
                                            runCatching {
                                                if (following) repo.unfollowUser(user.userId) else repo.followUser(user.userId)
                                            }
                                        if (res.isSuccess) {
                                            onFollowChanged()
                                        } else {
                                            users = prev
                                        }
                                    }
                                }
                                if (following) {
                                    OutlinedButton(
                                        onClick = toggleFollow,
                                        shape = RectangleShape,
                                        border = BorderStroke(1.dp, SteelBorder),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "SIGUIENDO",
                                            color = PureWhite,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = toggleFollow,
                                        shape = RectangleShape,
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = PureWhite,
                                                contentColor = PureBlack,
                                            ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "SEGUIR",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
