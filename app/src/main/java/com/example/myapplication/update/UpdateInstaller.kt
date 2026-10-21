package com.example.myapplication.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Передача скачанного APK системному установщику (окно «Установить?»). Сессия `PackageInstaller` с
 * ожиданием ответа убрана: на HyperOS она либо долго молчала, либо проверка пакета, начатая в ней,
 * сбрасывалась через 45 с и шла заново в обычном установщике.
 */
class UpdateInstaller(private val context: Context) {

    /** Разрешил ли пользователь этому приложению устанавливать пакеты. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Бросить все незавершённые сессии этого приложения. На HyperOS сессия может молча застрять после
     * `commit`: система начинает установку, но ни окна подтверждения, ни результата не присылает.
     */
    fun abandonOwnSessions() {
        val installer = context.packageManager.packageInstaller
        runCatching {
            installer.mySessions.forEach { info ->
                Log.i(TAG, "abandon session ${info.sessionId} (progress ${info.progress})")
                runCatching { installer.abandonSession(info.sessionId) }
            }
        }.onFailure { Log.w(TAG, "could not list own sessions", it) }
    }

    /** Намерение «установить этот APK» (файл отдаётся через FileProvider). */
    fun systemInstallIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Открыть окно системного установщика сразу. false — на устройстве нет обработчика. */
    fun openWithSystemInstaller(file: File): Boolean = runCatching {
        context.startActivity(systemInstallIntent(file))
        Log.i(TAG, "opened ${file.name} with the system installer")
        true
    }.getOrElse {
        Log.w(TAG, "could not open the system installer", it)
        false
    }

    private companion object {
        const val TAG = "AppUpdate"
    }
}
