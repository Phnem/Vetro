package com.example.myapplication.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Фоновая проверка обновления: раз в [AppUpdateScheduler.PERIOD_HOURS] часов спрашивает релиз, по
 * неплатной сети качает файл и пробует его поставить. Все решения принимает [AppUpdateManager];
 * сюда выносится только то, что должно переживать закрытие приложения.
 */
class AppUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val manager: AppUpdateManager by inject()

    override suspend fun doWork(): Result = try {
        manager.runBackgroundCycle()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Следующий проход через несколько часов: повторять немедленно по плохой сети смысла нет.
        Result.success()
    }
}

object AppUpdateScheduler {

    const val PERIOD_HOURS = 12L
    private const val UNIQUE_NAME = "app_update_check"

    /** Включает периодическую проверку при [UpdateMode.Auto] и снимает её в остальных режимах. */
    fun sync(context: Context, mode: UpdateMode) {
        val work = WorkManager.getInstance(context)
        if (mode != UpdateMode.Auto) {
            work.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        work.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
