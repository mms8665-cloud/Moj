package ir.moj.radio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.moj.radio.data.CameraQuality
import ir.moj.radio.data.UserSettings
import ir.moj.radio.ui.theme.MojNavyDark
import ir.moj.radio.ui.theme.MojRedAlert

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    isConnected: Boolean,
    staApSupported: Boolean,
    onSaveNickname: (String) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onRogerBeepToggle: (Boolean) -> Unit,
    onCameraQualityChange: (CameraQuality) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onOpenGuide: () -> Unit,
    onLeaveNetwork: () -> Unit,
    onBack: () -> Unit
) {
    var nickInput by remember(settings.nickname) { mutableStateOf(settings.nickname) }

    Scaffold(
        containerColor = MojNavyDark,
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات موج") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = nickInput,
                onValueChange = { if (it.length <= 20) nickInput = it },
                label = { Text("نام مستعار (۲ تا ۲۰ کاراکتر)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSaveNickname(nickInput) },
                enabled = nickInput.trim().length in 2..20
            ) {
                Text("ذخیره نام مستعار")
            }

            HorizontalDivider()

            Text("بلندی صدای دریافتی: ${(settings.outputVolume * 100).toInt()}٪", fontWeight = FontWeight.Medium)
            Slider(value = settings.outputVolume, onValueChange = onVolumeChange)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("بوق پایان مکالمه (Roger Beep)")
                Switch(checked = settings.rogerBeepEnabled, onCheckedChange = onRogerBeepToggle)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("نمایش اعلان‌ها")
                Switch(checked = settings.notificationsEnabled, onCheckedChange = onNotificationsToggle)
            }

            HorizontalDivider()

            Text("کیفیت تصویر دوربین زنده", fontWeight = FontWeight.Medium)
            CameraQuality.entries.forEach { quality ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.cameraQuality == quality,
                        onClick = { onCameraQualityChange(quality) }
                    )
                    Text(quality.labelFa)
                }
            }

            HorizontalDivider()

            Text("وضعیت شبکه: ${if (isConnected) "متصل" else "قطع"}")
            Text("پشتیبانی هم‌زمان Wi-Fi و Hotspot: ${if (staApSupported) "دارد" else "ندارد"}")
            Text("نسخه برنامه: 1.0.0 (ir.moj.radio)")

            OutlinedButton(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth()) {
                Text("راهنمای استفاده از موج")
            }

            if (isConnected) {
                Button(
                    onClick = onLeaveNetwork,
                    colors = ButtonDefaults.buttonColors(containerColor = MojRedAlert),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("خروج از شبکه")
                }
            }
        }
    }
}
