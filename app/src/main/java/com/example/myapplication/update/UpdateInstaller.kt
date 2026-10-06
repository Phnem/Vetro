package com.example.myapplication.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Установка через сессию [PackageInstaller], а не через `ACTION_VIEW` на APK, как было: система
 * сообщает результат нашему приёмнику ([InstallResultReceiver]), поэтому окно знает, поставилось
 * ли обновление, а не гадает.
 *
 * С Android 12 сессия просит ставить без подтверждения (USER_ACTION_NOT_REQUIRED). Система соглашается,
 * когда приложение уже ставило само себя и не открыто на экране; иначе вернёт запрос подтверждения,
 * который приёмник превратит в уведомление. До Android 12 подтверждение нужно всегда.
 */
class UpdateInstaller(private val context: Context) {

    /** Разрешил ли пользователь этому приложению устанавливать пакеты. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Передать APK системе. Возвращает false, если сессию не удалось даже открыть; итог установки
     * приходит позже в [InstallResultReceiver].
     */
    fun install(file: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite(SESSION_ENTRY, 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = Intent(context, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                .setPackage(context.packageName)
            // Система дописывает в интент статус и запрос подтверждения - он должен быть изменяемым.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, result, flags)
            session.commit(pending.intentSender)
        }
        true
    }.getOrElse { false }

    private companion object {
        const val SESSION_ENTRY = "vetro-update.apk"
    }
}
