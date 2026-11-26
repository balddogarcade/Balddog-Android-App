package dog.bald.arcade.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dog.bald.arcade.R
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.data.toUserMessage
import dog.bald.arcade.ui.theme.CarbonSurface
import dog.bald.arcade.ui.theme.CharcoalBorder
import dog.bald.arcade.ui.theme.MutedGray
import dog.bald.arcade.ui.theme.ObsidianBlack
import dog.bald.arcade.ui.theme.PureBlack
import dog.bald.arcade.ui.theme.PureWhite
import dog.bald.arcade.ui.theme.SilverGray
import dog.bald.arcade.ui.theme.SpaceGroteskFamily
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    repo: ArcadeRepository,
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var isRegister by remember { mutableStateOf(false) }
    var isRecovery by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var awaitingGoogleOAuth by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var needsEmailVerification by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && awaitingGoogleOAuth) {
                    awaitingGoogleOAuth = false
                    loading = false
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(ObsidianBlack)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_text),
            contentDescription = "Bald Dog",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxWidth(0.75f)
                    .widthIn(max = 280.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "El patio de recreo de los perros calvos.",
            color = MutedGray,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CarbonSurface),
            border = BorderStroke(1.dp, CharcoalBorder),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text =
                        when {
                            isRecovery -> "RECUPERAR CONTRASEÑA"
                            isRegister -> "CREAR CUENTA"
                            else -> "INICIAR SESIÓN"
                        },
                    color = PureWhite,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )

                val fieldColors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PureWhite,
                        unfocusedBorderColor = CharcoalBorder,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedLabelColor = PureWhite,
                        unfocusedLabelColor = MutedGray,
                    )

                if (isRegister) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Jugador (Username)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!isRecovery) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (infoMsg != null) {
                    Text(
                        text = infoMsg ?: "",
                        color = PureWhite,
                        fontSize = 12.sp,
                    )
                }

                if (errorMsg != null) {
                    Text(
                        text = errorMsg ?: "",
                        color = SilverGray,
                        fontSize = 12.sp,
                    )
                }

                val trimmedUsername = username.trim()
                val usernameValid = !isRegister || trimmedUsername.matches(Regex("^[a-zA-Z0-9_]{3,20}$"))

                if (isRegister && username.isNotEmpty() && !usernameValid) {
                    Text(
                        text = "Username: 3 a 20 caracteres (letras, números y _)",
                        color = SilverGray,
                        fontSize = 11.sp,
                    )
                }

                Button(
                    enabled = !loading && email.isNotBlank() && (isRecovery || (password.length >= 6 && usernameValid)),
                    shape = RectangleShape,
                    onClick = {
                        scope.launch {
                            loading = true
                            errorMsg = null
                            infoMsg = null
                            try {
                                if (isRecovery) {
                                    repo.resetPasswordForEmail(email)
                                    infoMsg = "Te enviamos un enlace a tu correo para restablecer la contraseña."
                                } else if (isRegister) {
                                    repo.signUpWithEmail(email, password, trimmedUsername)
                                    onAuthenticated()
                                } else {
                                    repo.signInWithEmail(email, password)
                                    onAuthenticated()
                                }
                            } catch (t: Throwable) {
                                val msg = t.toUserMessage("Error de autenticación")
                                if (isRegister && msg.contains("verifica tu correo", ignoreCase = true)) {
                                    needsEmailVerification = true
                                    infoMsg = msg
                                } else {
                                    errorMsg = msg
                                }
                            } finally {
                                loading = false
                            }
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = PureWhite,
                            contentColor = PureBlack,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) {
                        CircularProgressIndicator(color = PureBlack, strokeWidth = 2.dp)
                    } else {
                        Text(
                            when {
                                isRecovery -> "ENVIAR ENLACE"
                                isRegister -> "REGISTRARME"
                                else -> "ENTRAR"
                            },
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (needsEmailVerification) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                loading = true
                                errorMsg = null
                                try {
                                    repo.resendVerificationEmail(email)
                                    infoMsg = "Correo de verificación reenviado."
                                } catch (t: Throwable) {
                                    errorMsg = t.toUserMessage("No se pudo reenviar el correo")
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Reenviar correo de verificación", color = PureWhite, fontSize = 12.sp)
                    }
                }

                if (!isRecovery) {
                    Button(
                        enabled = !loading,
                        shape = RectangleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = PureWhite,
                                contentColor = androidx.compose.ui.graphics.Color.Black,
                            ),
                        onClick = {
                            scope.launch {
                                loading = true
                                awaitingGoogleOAuth = true
                                errorMsg = null
                                infoMsg = "Completa el inicio de sesión en Google..."
                                try {
                                    repo.signInWithGoogle()
                                } catch (t: Throwable) {
                                    awaitingGoogleOAuth = false
                                    loading = false
                                    infoMsg = null
                                    errorMsg = t.toUserMessage("Error al iniciar con Google")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_google),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "CONTINUAR CON GOOGLE",
                            color = androidx.compose.ui.graphics.Color.Black,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!isRegister && !isRecovery) {
                        TextButton(
                            onClick = {
                                isRecovery = true
                                errorMsg = null
                                infoMsg = null
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 32.dp),
                        ) {
                            Text("¿Olvidaste tu contraseña?", color = MutedGray, fontSize = 12.sp)
                        }
                    }

                    TextButton(
                        onClick = {
                            if (isRecovery) {
                                isRecovery = false
                            } else {
                                isRegister = !isRegister
                            }
                            errorMsg = null
                            infoMsg = null
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 32.dp),
                    ) {
                        Text(
                            text =
                                when {
                                    isRecovery -> "Volver al login"
                                    isRegister -> "¿Ya tienes cuenta? Inicia sesión"
                                    else -> "¿Nuevo aquí? Crea una cuenta"
                                },
                            color = MutedGray,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
