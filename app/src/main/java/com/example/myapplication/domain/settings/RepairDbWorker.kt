package com.example.myapplication.domain.settings

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.utils.getDevRepairDbStrings
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Фоновое «Исправление БД»: пользователь нажал кнопку и может уйти с экрана / свернуть
 * приложение — WorkManager доведёт проход до конца и отдаст результат в [RepairDbCoordinator].
 */
class RepairDbWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val repairAnimeDbUseCase: RepairAnimeDbUseCase by inject()
    private val coordinator: RepairDbCoordinator by inject()

    override suspend fun doWork(): Result {
        val language = runCatching {
            AppLanguage.valueOf(inputData.getString(KEY_LANGUAGE) ?: "")
        }.getOrDefault(AppLanguage.RU)
        val contentType = runCatching {
            AppContentType.valueOf(inputData.getString(KEY_CONTENT_TYPE) ?: "")
        }.getOrDefault(AppContentType.ANIME)

        val strings = getDevRepairDbStrings(language)
        val sessionLog = RepairDbSessionLog()
        return try {
            val result = repairAnimeDbUseCase(
                language = language,
                contentType = contentType,
                sessionLog = sessionLog,
                onProgress = { processed, total -> coordinator.onProgress(processed, total) },
            )
            val message = if (result.repairedCount == 0 && result.failedCount == 0) {
                strings.resultNothing
            } else {
                strings.resultTemplate.format(
                    result.repairedCount,
                    result.scannedCount,
                    result.skippedCount,
                    result.failedCount,
                )
            }
            coordinator.onFinished(message = message, log = sessionLog.asText(), success = true)
            Result.success()
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            sessionLog.error("Repair aborted", e)
            Log.w(TAG, "Repair DB worker failed", e)
            coordinator.onFinished(
                message = strings.resultFailed,
                log = sessionLog.asText(),
                success = false,
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_CONTENT_TYPE = "contentType"
        private const val TAG = "RepairDbWorker"
    }
}
