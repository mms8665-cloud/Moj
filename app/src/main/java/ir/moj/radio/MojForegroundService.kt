package ir.moj.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MojForegroundService : Service() {

    private val binder = LocalBinder()
    val meshEngine by lazy { MojMeshEngine(applicationContext) }

    inner class LocalBinder : Binder() {
        fun getService(): MojForegroundService = this@MojForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        meshEngine.startEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        meshEngine.stopEngine()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channelId = "moj_radio_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "سرویس بی‌سیم موج",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("بی‌سیم موج (MOJ) فعال است")
            .setContentText("ارتباط شبکه محلی و PTT برقرار است")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build()
    }
}
