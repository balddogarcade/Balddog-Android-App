package dog.bald.arcade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeMagenta
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.BaldDogArcadeTheme
import dog.bald.arcade.ui.theme.CoinYellow
import dog.bald.arcade.ui.theme.DeepPurple
import dog.bald.arcade.ui.theme.LeatherBlack
import dog.bald.arcade.ui.theme.RetroIvory
import dog.bald.arcade.ui.theme.SteelGray
import uniffi.balddog_android.EngineStatus
import uniffi.balddog_android.getRecentLogs
import uniffi.balddog_android.pingEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val nativeLibDir = applicationInfo.nativeLibraryDir

        setContent {
            BaldDogArcadeTheme {
                Scaffold(
                    containerColor = ArcadeBlack
                ) { innerPadding ->
                    EngineStatusScreen(
                        nativeLibDir = nativeLibDir,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun EngineStatusScreen(
    nativeLibDir: String,
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf<EngineStatus?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refresh() {
        try {
            status = pingEngine(nativeLibDir)
            logs = getRecentLogs()
            errorMsg = null
        } catch (t: Throwable) {
            errorMsg = t.message ?: "Error desconocido al conectar con el motor Rust"
        }
    }

    // Ping inicial al montar la pantalla
    remember(nativeLibDir) {
        refresh()
        true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ArcadeBlack)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "bald.dog ARCADE",
            color = CoinYellow,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "Arcade with Teeth. · Android ARM64 Engine",
            color = SteelGray,
            fontSize = 14.sp
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = LeatherBlack),
            border = BorderStroke(1.dp, ArcadeMagenta),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "RUST ↔ KOTLIN UNIFFI BRIDGE",
                    color = ArcadeMagenta,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                val s = status
                if (s != null) {
                    Text("Motor: v${s.engineVersion}", color = RetroIvory)
                    Text("Arquitectura: ${s.targetArch}", color = RetroIvory)
                    Text(
                        text = if (s.fbneoReady) "FBNeo Core: LISTO (arm64-v8a)" else "FBNeo Core: NO ENCONTRADO",
                        color = if (s.fbneoReady) CoinYellow else ArcadeOrange,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (errorMsg != null) {
                    Text("Error: $errorMsg", color = ArcadeOrange)
                }
            }
        }

        Button(
            onClick = { refresh() },
            colors = ButtonDefaults.buttonColors(
                containerColor = CoinYellow,
                contentColor = ArcadeBlack
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("PING MOTOR RUST", fontWeight = FontWeight.Bold)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(DeepPurple, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        text = "LOGS INTERNOS (RUST):",
                        color = SteelGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(logs) { line ->
                    Text(
                        text = line,
                        color = RetroIvory,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
