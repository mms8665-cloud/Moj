package ir.moj.radio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ir.moj.radio.MainActivity
import ir.moj.radio.R

class MojForegroundService : Service() {

    companion object {
        const val CHANNEL_SERVICE = "moj_service_channel"
        const val CHANNEL_EVENTS = "moj_events_channel"
        const val FOREGROUND_ID = 1001

        fun start(context: Context, statusText: String = "متصل به شبکه محلی موج") {
            val intent = Intent(context, MojForegroundService::class.java).apply {
                putExtra("status", statusText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MojForegroundService::class.java))
        }

        fun notifyEvent(context: Context, title: String, message: String, notificationId: Int = System.currentTimeMillis().toInt()) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannels(manager)
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification_moj)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
            manager.notify(notificationId, notification)
        }

        private fun ensureChannels(manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val serviceChannel = NotificationChannel(
                    CHANNEL_SERVICE,
                    "سرویس بی‌سیم موج",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "نگهداری ارتباط آفلاین موج در پس‌زمینه" }

                val eventsChannel = NotificationChannel(
                    CHANNEL_EVENTS,
                    "اعلان‌های شبکه موج",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "پیام‌ها، فایل‌ها، درخواست عضویت و دوربین زنده" }

                manager.createNotificationChannel(serviceChannel)
                manager.createNotificationChannel(eventsChannel)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MOJ::RadioWakeLock").apply {
            acquire(60 * 60 * 1000L) // تا یک ساعت و تمدید خودکار هنگام فعالیت
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra("status") ?: "سرویس بی‌سیم موج فعال است"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(manager)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification_moj)
            .setContentTitle("بی‌سیم گروهی موج (MOJ)")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()

        startForeground(FOREGROUND_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
