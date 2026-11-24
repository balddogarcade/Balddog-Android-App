package dog.bald.arcade.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.CountryCatalog
import dog.bald.arcade.data.ProfileDto
import dog.bald.arcade.data.SocialLinksDto
import dog.bald.arcade.data.SocialValidation
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.DangerRedBorder
import dog.bald.arcade.ui.theme.JetBrainsMonoFamily
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import dog.bald.arcade.ui.theme.SteelBorder
@Composable
fun EditProfileDialog(
    profile: ProfileDto,
    fallbackAvatarSeed: String,
    saving: Boolean,
    errorMsg: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        username: String,
        bio: String?,
        countryCode: String?,
        avatarSeed: String,
        isPrivate: Boolean,
        socialLinks: SocialLinksDto
    ) -> Unit
) {
    var username by remember(profile.id) { mutableStateOf(profile.username) }
    var bio by remember(profile.id) { mutableStateOf(profile.bio.orEmpty()) }
    var countryCode by remember(profile.id) { mutableStateOf(profile.countryCode) }
    var avatarSeed by remember(profile.id) {
        mutableStateOf(profile.avatarSeed?.takeIf { it.isNotBlank() } ?: fallbackAvatarSeed)
    }
    var isPrivate by remember(profile.id) { mutableStateOf(profile.isPrivate) }

    var xUrl by remember(profile.id) { mutableStateOf(profile.socialLinks.x.orEmpty()) }
    var igUrl by remember(profile.id) { mutableStateOf(profile.socialLinks.instagram.orEmpty()) }
    var ytUrl by remember(profile.id) { mutableStateOf(profile.socialLinks.youtube.orEmpty()) }
    var twitchUrl by remember(profile.id) { mutableStateOf(profile.socialLinks.twitch.orEmpty()) }
    var discordVal by remember(profile.id) { mutableStateOf(profile.socialLinks.discord.orEmpty()) }
    var githubUrl by remember(profile.id) { mutableStateOf(profile.socialLinks.github.orEmpty()) }
    var webUrl by remember(profile.id) { mutableStateOf(profile.socialLinks.website.orEmpty()) }

    var countryMenuExpanded by remember { mutableStateOf(false) }

    val draftLinks = SocialLinksDto(
        x = xUrl.trim().ifEmpty { null },
        instagram = igUrl.trim().ifEmpty { null },
        youtube = ytUrl.trim().ifEmpty { null },
        twitch = twitchUrl.trim().ifEmpty { null },
        discord = discordVal.trim().ifEmpty { null },
        github = githubUrl.trim().ifEmpty { null },
        website = webUrl.trim().ifEmpty { null }
    )
    val usernameError = SocialValidation.validateUsername(username)
    val socialErrors = remember(draftLinks) { SocialValidation.validateSocialLinks(draftLinks) }
    val bioError = if (bio.length > 140) "Máximo 140 caracteres" else null
    val formValid = usernameError == null && socialErrors.isEmpty() && bioError == null

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PureWhite,
        unfocusedTextColor = PureWhite,
        focusedBorderColor = PureWhite,
        unfocusedBorderColor = SteelBorder,
        cursorColor = PureWhite,
        focusedLabelColor = PureWhite,
        unfocusedLabelColor = MutedGray
    )

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = CarbonSurface,
        shape = RectangleShape,
        title = {
            Text(
                text = "EDITAR PERFIL",
                color = PureWhite,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(CarbonSurface, RectangleShape)
                            .border(1.dp, SteelBorder, RectangleShape)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DiceBearPixelArtAvatar(
                            seed = avatarSeed,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AVATAR PIXEL-ART",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Generado al azar",
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    OutlinedButton(
                        enabled = !saving,
                        onClick = { avatarSeed = randomAvatarSeed(avatarSeed) },
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, SteelBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Casino,
                            contentDescription = null,
                            tint = PureWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ALEATORIO",
                            color = PureWhite,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { if (it.length <= 20) username = it },
                    singleLine = true,
                    enabled = !saving,
                    label = { Text("Username (3–20 chars)") },
                    isError = usernameError != null,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                if (usernameError != null) {
                    Text(text = usernameError, color = DangerRedBorder, fontSize = 11.sp)
                }

                OutlinedTextField(
                    value = bio,
                    onValueChange = { if (it.length <= 140) bio = it },
                    maxLines = 3,
                    enabled = !saving,
                    label = { Text("Bio (${bio.length}/140)") },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    val selectedCountry = CountryCatalog.findByCode(countryCode)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianBlack, RectangleShape)
                            .border(1.dp, SteelBorder, RectangleShape)
                            .clickable(enabled = !saving) { countryMenuExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (selectedCountry != null) {
                                "${selectedCountry.flagEmoji()}  ${selectedCountry.name} (${selectedCountry.code})"
                            } else {
                                "País: Sin especificar"
                            },
                            color = PureWhite,
                            fontSize = 13.sp
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = SilverGray
                        )
                    }
                    DropdownMenu(
                        expanded = countryMenuExpanded,
                        onDismissRequest = { countryMenuExpanded = false },
                        modifier = Modifier
                            .background(CarbonSurface)
                            .heightIn(max = 260.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sin especificar", color = MutedGray, fontSize = 12.sp) },
                            onClick = {
                                countryCode = null
                                countryMenuExpanded = false
                            }
                        )
                        CountryCatalog.countries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${option.flagEmoji()}  ${option.name} (${option.code})",
                                        color = PureWhite,
                                        fontSize = 12.sp
                                    )
                                },
                                onClick = {
                                    countryCode = option.code
                                    countryMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBlack, RectangleShape)
                        .border(1.dp, CharcoalBorder, RectangleShape)
                        .clickable(enabled = !saving) { isPrivate = !isPrivate }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPrivate,
                        onCheckedChange = { if (!saving) isPrivate = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = PureWhite,
                            checkmarkColor = PureBlack,
                            uncheckedColor = SteelBorder
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Perfil privado",
                            color = PureWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Oculta redes sociales, tiempo e historial a otros jugadores",
                            color = MutedGray,
                            fontSize = 10.sp
                        )
                    }
                }

                HorizontalDivider(color = CharcoalBorder)

                Text(
                    text = "REDES SOCIALES (URL https:// completa)",
                    color = SilverGray,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )

                SocialLinkField("X / Twitter (https://x.com/...)", xUrl, { xUrl = it }, socialErrors["x"], !saving, fieldColors)
                SocialLinkField("Instagram (https://instagram.com/...)", igUrl, { igUrl = it }, socialErrors["instagram"], !saving, fieldColors)
                SocialLinkField("YouTube (https://youtube.com/...)", ytUrl, { ytUrl = it }, socialErrors["youtube"], !saving, fieldColors)
                SocialLinkField("Twitch (https://twitch.tv/...)", twitchUrl, { twitchUrl = it }, socialErrors["twitch"], !saving, fieldColors)
                SocialLinkField("Discord (https://discord.gg/... o tag)", discordVal, { discordVal = it }, socialErrors["discord"], !saving, fieldColors)
                SocialLinkField("GitHub (https://github.com/...)", githubUrl, { githubUrl = it }, socialErrors["github"], !saving, fieldColors)
                SocialLinkField("Web personal (https://...)", webUrl, { webUrl = it }, socialErrors["website"], !saving, fieldColors)

                if (!errorMsg.isNullOrBlank()) {
                    Text(
                        text = errorMsg,
                        color = DangerRedBorder,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && formValid,
                shape = RectangleShape,
                onClick = {
                    onConfirm(
                        username.trim(),
                        bio.trim().ifEmpty { null },
                        countryCode,
                        avatarSeed,
                        isPrivate,
                        draftLinks
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PureWhite,
                    contentColor = PureBlack
                )
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = PureBlack,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Text(
                        text = "GUARDAR",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onDismiss
            ) {
                Text("CANCELAR", color = SilverGray)
            }
        }
    )
}

@Composable
private fun SocialLinkField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    colors: androidx.compose.material3.TextFieldColors
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            label = { Text(label, fontSize = 11.sp) },
            colors = colors,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(text = error, color = DangerRedBorder, fontSize = 10.sp)
        }
    }
}
