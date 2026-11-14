package ir.moj.radio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    private var mojService by mutableStateOf<MojForegroundService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MojForegroundService.LocalBinder
            mojService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mojService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (_: Exception) {}

        try {
            val serviceIntent = Intent(this, MojForegroundService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {}

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00BFA5),
                    secondary = Color(0xFF64FFDA),
                    background = Color(0xFF101418),
                    surface = Color(0xFF1C2228)
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val engine = mojService?.meshEngine
                    if (engine == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        MainContent(engine)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            try { unbindService(connection) } catch (_: Exception) {}
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun MainContent(engine: MojMeshEngine) {
    val nickname by engine.nickname.collectAsState()
    val statusBanner by engine.statusBanner.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        launcher.launch(perms.toTypedArray())
    }

    if (nickname.length !in 2..20) {
        NicknameSetupScreen(engine)
    } else {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("بی‌سیم موج (MOJ) متصل است", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text("نام مستعار: $nickname", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(10.dp))
                    Text(statusBanner, fontSize = 12.sp, color = Color.LightGray, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun NicknameSetupScreen(engine: MojMeshEngine) {
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تنظیم نام مستعار موج", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if(it.length <= 20) name = it; err = false },
                    label = { Text("نام مستعار (۲ تا ۲۰ کاراکتر)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (err) {
                    Spacer(Modifier.height(4.dp))
                    Text("نام باید بین ۲ تا ۲۰ کاراکتر باشد", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (!engine.saveNickname(name)) err = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ورود به موج")
                }
            }
        }
    }
}
