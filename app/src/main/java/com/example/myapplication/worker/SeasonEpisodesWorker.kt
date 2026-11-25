package com.example.myapplication.worker

import com.example.myapplication.utils.runSuspendCatching
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myapplication.domain.seasons.SeasonEpisodesResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Разовый прогон резолвера «серии по сезонам» при старте приложения — чтобы данные появились
 * сразу, не дожидаясь 6-часового периодического [AnimeUpdateWorker] (который тоже зовёт
 * refreshStale). Бюджет крупнее фонового: за пару запусков покрывает всю коллекцию.
 */
class SeasonEpisodesWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val resolver: SeasonEpisodesResolver by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runSuspendCatching { resolver.refreshStale(budget = STARTUP_BUDGET) }
            .onFailure { it.printStackTrace() }
        Result.success()
    }

    companion object {
        private const val STARTUP_BUDGET = 15

        /** Поставить разовый прогон (KEEP: уже идущий не дублируем). */
        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SeasonEpisodesWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "SeasonEpisodesStartup",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
