package ir.moj.radio.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "moj_user_prefs")

enum class CameraQuality(val labelFa: String, val jpegQuality: Int, val targetFps: Int, val width: Int, val height: Int) {
    LOW("پایین (صرفه‌جویی شبکه)", 40, 8, 320, 240),
    MEDIUM("متوسط (پیشنهادی)", 60, 12, 480, 360),
    HIGH("بالا (کیفیت بیشتر)", 75, 15, 640, 480)
}

data class UserSettings(
    val userId: String,
    val nickname: String,
    val rogerBeepEnabled: Boolean,
    val outputVolume: Float,
    val cameraQuality: CameraQuality,
    val notificationsEnabled: Boolean
)

class UserPrefs(private val context: Context) {
    companion object {
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_NICKNAME = stringPreferencesKey("nickname")
        private val KEY_ROGER_BEEP = booleanPreferencesKey("roger_beep")
        private val KEY_VOLUME = floatPreferencesKey("output_volume")
        private val KEY_CAM_QUALITY = stringPreferencesKey("camera_quality")
        private val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val savedId = prefs[KEY_USER_ID] ?: ""
        val qualityName = prefs[KEY_CAM_QUALITY] ?: CameraQuality.MEDIUM.name
        val quality = runCatching { CameraQuality.valueOf(qualityName) }.getOrDefault(CameraQuality.MEDIUM)
        UserSettings(
            userId = savedId,
            nickname = prefs[KEY_NICKNAME] ?: "",
            rogerBeepEnabled = prefs[KEY_ROGER_BEEP] ?: true,
            outputVolume = prefs[KEY_VOLUME] ?: 1.0f,
            cameraQuality = quality,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: true
        )
    }

    suspend fun ensureUserId(): String {
        val current = context.dataStore.data.first()[KEY_USER_ID]
        if (!current.isNullOrBlank()) return current
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { it[KEY_USER_ID] = newId }
        return newId
    }

    suspend fun saveNickname(nickname: String): Boolean {
        val trimmed = nickname.trim()
        if (trimmed.length !in 2..20) return false
        ensureUserId()
        context.dataStore.edit { it[KEY_NICKNAME] = trimmed }
        return true
    }

    suspend fun setRogerBeep(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ROGER_BEEP] = enabled }
    }

    suspend fun setOutputVolume(volume: Float) {
        context.dataStore.edit { it[KEY_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setCameraQuality(quality: CameraQuality) {
        context.dataStore.edit { it[KEY_CAM_QUALITY] = quality.name }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
    }
}
