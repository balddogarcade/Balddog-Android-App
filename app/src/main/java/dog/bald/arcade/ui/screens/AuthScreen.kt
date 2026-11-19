package dog.bald.arcade.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.data.ArcadeRepository
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeMagenta
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.CoinYellow
import dog.bald.arcade.ui.theme.LeatherBlack
import dog.bald.arcade.ui.theme.RetroIvory
import dog.bald.arcade.ui.theme.SteelGray
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    repo: ArcadeRepository,
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ArcadeBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "bald.dog ARCADE",
            color = CoinYellow,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "Arcade with Teeth.",
            color = ArcadeMagenta,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = LeatherBlack),
            border = BorderStroke(1.dp, ArcadeMagenta),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isRegister) "CREAR CUENTA" else "INSERT COIN · LOGIN",
                    color = RetroIvory,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ArcadeMagenta,
                    unfocusedBorderColor = SteelGray,
                    focusedTextColor = RetroIvory,
                    unfocusedTextColor = RetroIvory,
                    focusedLabelColor = CoinYellow,
                    unfocusedLabelColor = SteelGray
                )

                if (isRegister) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Jugador (Username)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg != null) {
                    Text(
                        text = errorMsg ?: "",
                        color = ArcadeOrange,
                        fontSize = 12.sp
                    )
                }

                Button(
                    enabled = !loading && email.isNotBlank() && password.length >= 6 && (!isRegister || username.isNotBlank()),
                    onClick = {
                        scope.launch {
                            loading = true
                            errorMsg = null
                            try {
                                if (isRegister) {
                                    repo.signUpWithEmail(email, password, username)
                                } else {
                                    repo.signInWithEmail(email, password)
                                }
                                onAuthenticated()
                            } catch (t: Throwable) {
                                errorMsg = t.message ?: "Error de autenticación"
                            } finally {
                                loading = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoinYellow,
                        contentColor = ArcadeBlack
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) {
                        CircularProgressIndicator(color = ArcadeBlack, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (isRegister) "REGISTRARME" else "ENTRAR",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                TextButton(
                    onClick = {
                        isRegister = !isRegister
                        errorMsg = null
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = if (isRegister) "¿Ya tienes cuenta? Inicia sesión" else "¿Nuevo aquí? Crea una cuenta",
                        color = SteelGray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            enabled = !loading,
            onClick = {
                scope.launch {
                    loading = true
                    errorMsg = null
                    try {
                        repo.signInAnonymously()
                        onAuthenticated()
                    } catch (t: Throwable) {
                        errorMsg = t.message ?: "No se pudo iniciar como invitado"
                    } finally {
                        loading = false
                    }
                }
            },
            border = BorderStroke(1.dp, ArcadeOrange),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "ENTRAR COMO INVITADO",
                color = ArcadeOrange,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
