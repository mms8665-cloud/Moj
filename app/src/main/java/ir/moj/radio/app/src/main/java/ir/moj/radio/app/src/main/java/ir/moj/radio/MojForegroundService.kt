package ir.moj.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class MojForegroundService : Service() {

    companion object {
        const val CHANNEL_ID_SERVICE = "moj_foreground_channel"
        const val CHANNEL_ID_EVENTS = "moj_events_channel"
        const val NOTIFICATION_ID = 1001
    }

    private val binder = LocalBinder()
    lateinit var meshEngine: MojMeshEngine
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var eventNotificationCounter = 2000

    inner class LocalBinder : Binder() {
        fun getService(): MojForegroundService = this@MojForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireLocks()
        meshEngine = MojMeshEngine(applicationContext)
        meshEngine.onNotificationEvent = { title, body ->
            showEventNotification(title, body)
        }
        startMojForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMojForeground()
        return START_STICKY
    }

    private fun startMojForeground() {
        val notification = buildPersistentNotification("بی‌سیم آفلاین موج در پس‌زمینه فعال است")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MOJ::MeshWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(lockType, "MOJ::MeshWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "سرویس دائمی بی‌سیم موج",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "حفظ ارتباط شبکه محلی و بی‌سیم هنگام خاموش بودن صفحه"
            }

            val eventChannel = NotificationChannel(
                CHANNEL_ID_EVENTS,
                "رویدادها و پیام‌های موج",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان پیام جدید، درخواست عضویت و دریافت فایل"
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(eventChannel)
        }
    }

    private fun buildPersistentNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("موج (MOJ) - بی‌سیم آفلاین")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showEventNotification(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID_EVENTS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(eventNotificationCounter++, notif)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        meshEngine.stopNetwork()
        super.onDestroy()
    }
}
