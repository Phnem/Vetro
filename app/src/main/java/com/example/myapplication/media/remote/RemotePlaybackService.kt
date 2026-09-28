package com.example.myapplication.media.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.myapplication.AppScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Пока идёт показ на ТВ, процесс нужен: прокси отдаёт телевизору сегменты, адаптер опрашивает его
 * состояние. Сервис переднего плана с уведомлением «Показ на …» и кнопкой «Остановить».
 */
class RemotePlaybackService : Service() {
    private val manager: RemotePlaybackManager by inject()
    private val appScope: AppScope by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appScope.launch { runCatching { manager.disconnect(stopPlayback = true) } }
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_DEVICE).orEmpty()
        ensureChannel()
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemotePlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val ru = resources.configuration.locales[0].language == "ru"
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.phnem.vetro.R.drawable.ph_television_simple_fill)
            .setContentTitle(if (ru) "Показ на «$name»" else "Playing on “$name”")
            .setContentText(if (ru) "Воспроизведение идёт на телевизоре" else "Playback is on the TV")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(open)
            .addAction(0, if (ru) "Остановить" else "Stop", stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Показ на телевизоре", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL = "remote_playback"
        private const val NOTIFICATION_ID = 466_410
        private const val ACTION_STOP = "com.phnem.vetro.remote.STOP"
        private const val EXTRA_DEVICE = "device"

        fun start(context: Context, deviceName: String) {
            runCatching {
                context.startForegroundService(Intent(context, RemotePlaybackService::class.java).putExtra(EXTRA_DEVICE, deviceName))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RemotePlaybackService::class.java)) }
        }
    }
}
