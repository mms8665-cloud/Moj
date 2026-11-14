package ir.moj.radio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.moj.radio.ui.theme.MojCyan
import ir.moj.radio.ui.theme.MojNavyDark

@Composable
fun WelcomeScreen(
    onSaveNickname: (String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MojNavyDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Radio,
            contentDescription = "آیکن موج",
            tint = MojCyan,
            modifier = Modifier.size(84.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "به بی‌سیم گروهی موج (MOJ) خوش آمدید",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ارتباط صوتی، پیام، فایل و دوربین زنده در شبکه محلی بدون اینترنت",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = nickname,
            onValueChange = {
                if (it.length <= 20) {
                    nickname = it
                    errorText = null
                }
            },
            label = { Text("نام مستعار شما (۲ تا ۲۰ کاراکتر)") },
            singleLine = true,
            isError = errorText != null,
            supportingText = {
                Text(errorText ?: "${nickname.trim().length} / 20 کاراکتر")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                val trimmed = nickname.trim()
                if (trimmed.length !in 2..20) {
                    errorText = "نام مستعار باید بین ۲ تا ۲۰ کاراکتر باشد."
                } else {
                    onSaveNickname(trimmed)
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("ورود به موج", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
