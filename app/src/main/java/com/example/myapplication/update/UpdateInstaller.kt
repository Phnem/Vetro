package com.example.myapplication.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
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
        // Недоигранные сессии прошлых попыток (в том числе зависшие) не нужны: начинаем с чистого листа.
        abandonOwnSessions()
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            // Пользователь ждёт на экране: система не компилирует пакет при установке (меньше времени и
            // нагрева), а доделывает это позже в фоне. Цена - первые запуски после обновления на JIT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite(SESSION_ENTRY, 0, file.length()).use { out ->
                    input.copyTo(out, COPY_BUFFER_BYTES)
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
    }.getOrElse {
        Log.w(TAG, "could not create or commit the install session", it)
        false
    }

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

    /**
     * Обычная установка файла системным установщиком (окно «Установить?»), как работало до сессий.
     * Запасной путь, когда сессия зависла: свой экран установщика есть на любой прошивке.
     */
    fun openWithSystemInstaller(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "opened ${file.name} with the system installer")
        true
    }.getOrElse {
        Log.w(TAG, "could not open the system installer", it)
        false
    }

    private companion object {
        const val TAG = "AppUpdate"
        const val SESSION_ENTRY = "vetro-update.apk"

        /** Копия APK в сессию: с буфером по умолчанию (8 КБ) 34 МБ уходили четырьмя тысячами записей. */
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
