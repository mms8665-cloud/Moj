package ir.moj.radio.security

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LicenseManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "moj_license_prefs"
        private const val KEY_FIRST_RUN = "first_run_ms"
        private const val KEY_LAST_SEEN = "last_seen_ms"
        private const val KEY_ACTIVATED = "is_activated"
        private const val KEY_TAMPERED = "clock_tampered"
        private const val TRIAL_DURATION_MS = 48L * 60L * 60L * 1000L // ۴۸ ساعت (۲ روز)

        // کلید مخفی شما (باید دقیقاً با کلید داخل فایل HTML یکی باشد)
        private const val SECRET_KEY = "MOJ_RADIO_SECRET_2026_KEY"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val now = System.currentTimeMillis()
        if (!prefs.contains(KEY_FIRST_RUN)) {
            prefs.edit()
                .putLong(KEY_FIRST_RUN, now)
                .putLong(KEY_LAST_SEEN, now)
                .apply()
        } else {
            val lastSeen = prefs.getLong(KEY_LAST_SEEN, now)
            if (now < lastSeen - 60_000L) {
                prefs.edit().putBoolean(KEY_TAMPERED, true).apply()
            } else if (now > lastSeen) {
                prefs.edit().putLong(KEY_LAST_SEEN, now).apply()
            }
        }
    }

    @SuppressLint("HardwareIds")
    fun getDeviceCode(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "MOJ_DEFAULT_ID"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("MOJ_DEV_$androidId".toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02X".format(it) }
    }

    fun isActivated(): Boolean = prefs.getBoolean(KEY_ACTIVATED, false)

    fun isFullAccessAllowed(): Boolean {
        if (isActivated()) return true
        if (prefs.getBoolean(KEY_TAMPERED, false)) return false
        val firstRun = prefs.getLong(KEY_FIRST_RUN, System.currentTimeMillis())
        val elapsed = System.currentTimeMillis() - firstRun
        return elapsed in 0..TRIAL_DURATION_MS
    }

    fun remainingTrialHours(): Long {
        if (isActivated() || prefs.getBoolean(KEY_TAMPERED, false)) return 0L
        val firstRun = prefs.getLong(KEY_FIRST_RUN, System.currentTimeMillis())
        val remainingMs = TRIAL_DURATION_MS - (System.currentTimeMillis() - firstRun)
        return (remainingMs / (60L * 60L * 1000L)).coerceAtLeast(0L)
    }

    fun activate(inputCode: String): Boolean {
        val cleanInput = inputCode.trim().uppercase().replace("-", "").replace(" ", "")
        val expected = generateExpectedCode(getDeviceCode())
        if (cleanInput == expected) {
            prefs.edit()
                .putBoolean(KEY_ACTIVATED, true)
                .putBoolean(KEY_TAMPERED, false)
                .apply()
            return true
        }
        return false
    }

    private fun generateExpectedCode(deviceCode: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(deviceCode.uppercase().toByteArray(Charsets.UTF_8))
        return raw.take(5).joinToString("") { "%02X".format(it) }
    }
}
