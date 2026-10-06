package com.example.myapplication.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.koin.core.context.GlobalContext

/**
 * Принимает от системы итог установки обновления: поставилось, нужно подтверждение, отказ. Не
 * экспортируется: интент приходит только через `PendingIntent`, который выдал [UpdateInstaller].
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        val koin = GlobalContext.getOrNull() ?: return
        koin.get<AppUpdateManager>().onInstallResult(status, message, confirm)
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.phnem.vetro.update.INSTALL_RESULT"
    }
}
