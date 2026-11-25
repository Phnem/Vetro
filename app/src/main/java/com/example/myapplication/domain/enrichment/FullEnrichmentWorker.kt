package com.example.myapplication.domain.enrichment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.domain.settings.RepairAnimeDbUseCase
import com.example.myapplication.domain.settings.RepairDbCoordinator
import com.example.myapplication.domain.settings.RepairDbSessionLog
import com.example.myapplication.domain.titles.TitleDubbingCoordinator
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.utils.getDevRepairDbStrings
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Модуль «Полное обогащение»: единый прогон обеих фаз.
 *  1. Поля — [RepairAnimeDbUseCase] целиком (включая reconcile MAL id: он заполняет внешние id,
 *     что помогает следующей фазе искать названия). Прогресс идёт в [RepairDbCoordinator], который
 *     слушает SettingsViewModel (фаза «поля»).
 *  2. Названия — стартуем [TitleDubbingCoordinator] с fullRescan; он сам догоняет остаток партиями
 *     и обрабатывает 429 (фаза «названия»).
 */
class FullEnrichmentWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val repairAnimeDbUseCase: RepairAnimeDbUseCase by inject()
    private val repairCoordinator: RepairDbCoordinator by inject()
    private val titleDubbingCoordinator: TitleDubbingCoordinator by inject()

    override suspend fun doWork(): Result {
        val language = runCatching {
            AppLanguage.valueOf(inputData.getString(KEY_LANGUAGE) ?: "")
        }.getOrDefault(AppLanguage.RU)
        val contentType = runCatching {
            AppContentType.valueOf(inputData.getString(KEY_CONTENT_TYPE) ?: "")
        }.getOrDefault(AppContentType.ANIME)

        val strings = getDevRepairDbStrings(language)
        val sessionLog = RepairDbSessionLog()
        // Сразу переводим фазу полей в Running (иначе sheet покажет «поля» лишь с первым onProgress).
        repairCoordinator.onProgress(0, 0)
        return try {
            val result = repairAnimeDbUseCase(
                language = language,
                contentType = contentType,
                sessionLog = sessionLog,
                onProgress = { processed, total -> repairCoordinator.onProgress(processed, total) },
            )
            val message = if (result.repairedCount == 0 && result.failedCount == 0) {
                strings.resultNothing
            } else {
                strings.resultTemplate.format(
                    result.repairedCount, result.scannedCount, result.skippedCount, result.failedCount,
                )
            }
            repairCoordinator.onFinished(message = message, log = sessionLog.asText(), success = true)
            // Фаза 2: названия (API + AI), полный рескан.
            titleDubbingCoordinator.start(language, fullRescan = true)
            // Ссылки «Где смотреть» идут ОТДЕЛЬНЫМ воркером (enqueue в startFullEnrichment),
            // чтобы не умирать вместе с перезапусками этого длинного воркера.
            Result.success()
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            sessionLog.error("Full enrichment (fields) aborted", e)
            Log.w(TAG, "Full enrichment worker failed", e)
            repairCoordinator.onFinished(
                message = strings.resultFailed, log = sessionLog.asText(), success = false,
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_CONTENT_TYPE = "contentType"
        private const val TAG = "FullEnrichmentWorker"
    }
}
