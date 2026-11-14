package ir.moj.radio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

val MojNavyDark = Color(0xFF0B132B)
val MojNavySurface = Color(0xFF152238)
val MojNavyCard = Color(0xFF1C2E4A)
val MojElectricBlue = Color(0xFF00B0FF)
val MojCyan = Color(0xFF00E5FF)
val MojRedAlert = Color(0xFFFF5252)
val MojGreenOnline = Color(0xFF00E676)

private val MojDarkColors = darkColorScheme(
    primary = MojCyan,
    onPrimary = MojNavyDark,
    secondary = MojElectricBlue,
    onSecondary = MojNavyDark,
    background = MojNavyDark,
    onBackground = Color.White,
    surface = MojNavySurface,
    onSurface = Color.White,
    surfaceVariant = MojNavyCard,
    onSurfaceVariant = Color(0xFFB0C4DE),
    error = MojRedAlert
)

@Composable
fun MojTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = MojDarkColors,
            content = content
        )
    }
}
