package com.example.myapplication.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.phnem.vetro.R

/**
 * Уведомления об обновлении. Нужны только когда само оно не прошло: система потребовала
 * подтверждения установки или не разрешено ставить пакеты. Пока приложение открыто, об этом
 * говорит окно обновления, и дублировать его шторкой не нужно.
 */
class UpdateNotifier(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(strings: UpdateStrings) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.notificationChannel, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = strings.notificationChannelDesc },
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Обновление скачано, система ждёт подтверждения: тап открывает системное окно установки. */
    fun showReady(strings: UpdateStrings, version: String, confirm: Intent?) {
        if (!canPost()) return
        ensureChannel(strings)
        val target = confirm?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } ?: openAppIntent()
        val tap = PendingIntent.getActivity(
            context,
            REQUEST_READY,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(strings.notificationReadyTitle)
                .setContentText(strings.notificationReadyText(version))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    /** Установка не разрешена: тап ведёт на страницу разрешения для этого приложения. */
    fun showNeedsPermission(strings: UpdateStrings, version: String) {
        if (!canPost()) return
        ensureChannel(strings)
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context,
            REQUEST_PERMISSION,
            settings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(strings.notificationReadyTitle)
                .setContentText("$version · ${strings.notificationPermissionText}")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun openAppIntent(): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private companion object {
        const val CHANNEL_ID = "app_update_channel"
        const val NOTIFICATION_ID = 0x0A9D
        const val REQUEST_READY = 0x0A9E
        const val REQUEST_PERMISSION = 0x0A9F
    }
}
