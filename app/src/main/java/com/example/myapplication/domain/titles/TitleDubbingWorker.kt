package com.example.myapplication.domain.titles

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.utils.getTitleDubbingStrings
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Фоновый «Дубляж названий»: заполняет `title_en` для записей без английского названия.
 *
 * По каждой записи: сначала API-обогащение ([TitleEnrichmentUseCase]); если english не нашли —
 * AI-перевод ([AiTitleTranslationUseCase]). Успех / уверенный «не найдено» выводят из очереди;
 * временный сбой AI оставляет запись для следующего прогона.
 *
 * Большие библиотеки обрабатываются партиями с throttle и self-reschedule (APPEND),
 * чтобы не упереться в лимит времени одного прогона WorkManager.
 */
class TitleDubbingWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val titleEnrichmentUseCase: TitleEnrichmentUseCase by inject()
    private val russianTitleEnrichmentUseCase: RussianTitleEnrichmentUseCase by inject()
    private val aiTitleTranslationUseCase: AiTitleTranslationUseCase by inject()
    private val localDataSource: AnimeLocalDataSource by inject()
    private val credentialsStore: AiCredentialsStore by inject()
    private val coordinator: TitleDubbingCoordinator by inject()

    override suspend fun doWork(): Result {
        val language = runCatching {
            AppLanguage.valueOf(inputData.getString(TitleDubbingCoordinator.KEY_LANGUAGE) ?: "")
        }.getOrDefault(AppLanguage.RU)
        val fullRescan = inputData.getBoolean(TitleDubbingCoordinator.KEY_FULL_RESCAN, false)
        val strings = getTitleDubbingStrings(language)

        return try {
            if (fullRescan) {
                localDataSource.resetTitleEnChecks()
                localDataSource.resetTitleRuChecks()
            }

            val hasAi = credentialsStore.getAllConnectedProviders().isNotEmpty()
            val totalEn = localDataSource.countAnimeNeedingTitleEn()
            val totalRu = localDataSource.countAnimeNeedingTitleRu()
            val total = totalEn + totalRu
            coordinator.noteSessionStart(total)
            if (total == 0) {
                val sessionFilled = coordinator.sessionEnrichedCount()
                val message = if (sessionFilled == 0) strings.resultNothing
                else strings.resultTemplate.format(sessionFilled, coordinator.sessionInitialTotal())
                coordinator.onFinished(message, success = true)
                return Result.success()
            }

            var processed = 0
            var enriched = 0
            var ranThisRun = 0
            // Записи с временным сбоем AI — не крутим их в этом же прогоне.
            val aiFailedIds = mutableSetOf<String>()
            // != null, если прогон остановлен из-за исчерпания квоты AI (429). Значение — пауза до продолжения.
            var rateLimitStopMs: Long? = null

            // 1) EN: API → AI fallback
            loopEn@ while (!isStopped) {
                val batch = localDataSource.getAnimeNeedingTitleEn(BATCH_SIZE)
                    .filter { it.id !in aiFailedIds }
                if (batch.isEmpty()) break

                for (anime in batch) {
                    if (isStopped || ranThisRun >= MAX_PER_RUN) break@loopEn

                    when (titleEnrichmentUseCase.enrich(anime, markOnFailure = !hasAi)) {
                        is TitleEnrichmentUseCase.Outcome.Enriched -> enriched++
                        TitleEnrichmentUseCase.Outcome.NoEnglishFound -> {
                            if (hasAi) {
                                when (val ai = translateWithBackoff(anime)) {
                                    is AiTitleTranslationUseCase.Outcome.Translated -> enriched++
                                    // Уверенный «нет english» — выводим из очереди.
                                    AiTitleTranslationUseCase.Outcome.NoEnglishFound ->
                                        localDataSource.markTitleEnChecked(anime.id)
                                    // Квота исчерпана надолго — не жжём прогон впустую: останавливаемся
                                    // и продолжим позже (см. хвост doWork). Запись не помечаем.
                                    is AiTitleTranslationUseCase.Outcome.RateLimited -> {
                                        rateLimitStopMs = ai.retryAfterMs
                                        Log.w(TAG, "AI rate limited, pausing pass at «${anime.title}» (retry in ${ai.retryAfterMs}ms)")
                                        break@loopEn
                                    }
                                    // Временный сбой сети/модели — не помечаем, повторим в следующем прогоне.
                                    AiTitleTranslationUseCase.Outcome.Failed,
                                    AiTitleTranslationUseCase.Outcome.NoProvider -> {
                                        aiFailedIds.add(anime.id)
                                        Log.w(TAG, "AI skip (retry later): ${anime.title} → $ai")
                                    }
                                }
                            }
                        }
                        TitleEnrichmentUseCase.Outcome.Skipped -> localDataSource.markTitleEnChecked(anime.id)
                    }

                    processed++
                    ranThisRun++
                    coordinator.onProgress(processed, total)
                    delay(THROTTLE_MS)
                }
            }

            // 2) RU: Shikimori reverse enrichment (после EN — titleEn помогает поиску)
            if (ranThisRun < MAX_PER_RUN) {
                loopRu@ while (!isStopped) {
                    val batch = localDataSource.getAnimeNeedingTitleRu(BATCH_SIZE)
                    if (batch.isEmpty()) break

                    for (anime in batch) {
                        if (isStopped || ranThisRun >= MAX_PER_RUN) break@loopRu

                        when (russianTitleEnrichmentUseCase.enrich(anime, markOnFailure = true)) {
                            is RussianTitleEnrichmentUseCase.Outcome.Enriched -> enriched++
                            RussianTitleEnrichmentUseCase.Outcome.NoRussianFound -> Unit
                            RussianTitleEnrichmentUseCase.Outcome.Skipped ->
                                localDataSource.markTitleRuChecked(anime.id)
                        }

                        processed++
                        ranThisRun++
                        coordinator.onProgress(processed, total)
                        delay(THROTTLE_MS)
                    }
                }
            }

            coordinator.noteEnriched(enriched)
            val remaining = localDataSource.countAnimeNeedingTitleEn() +
                localDataSource.countAnimeNeedingTitleRu()
            val sessionFilled = coordinator.sessionEnrichedCount()
            val sessionTotal = coordinator.sessionInitialTotal().coerceAtLeast(total)
            Log.i(
                TAG,
                "run done: processed=$processed enriched=$enriched sessionFilled=$sessionFilled " +
                    "remaining=$remaining hasAi=$hasAi totalQueued=$total",
            )
            // Продолжаем только если очередь реально уменьшилась (иначе AI-сбои → бесконечный цикл).
            val madeQueueProgress = remaining < total
            when {
                // Исчерпана квота AI: это не провал — продолжим позже, когда лимит восстановится.
                // Планируем продолжение даже без прогресса очереди (иначе застрянем на resultNoneFilled).
                rateLimitStopMs != null && remaining > 0 && !isStopped -> {
                    val delayMs = rateLimitStopMs!!.coerceIn(CONTINUATION_DELAY_MS, MAX_CONTINUATION_DELAY_MS)
                    Log.i(TAG, "Quota exhausted, resuming dubbing in ${delayMs}ms")
                    coordinator.scheduleContinuation(language, delayMs = delayMs)
                    coordinator.onProgress(processed, total)
                }
                remaining > 0 && !isStopped && madeQueueProgress -> {
                    coordinator.scheduleContinuation(language, delayMs = CONTINUATION_DELAY_MS)
                    coordinator.onProgress(processed, total)
                }
                else -> {
                    val message = when {
                        sessionFilled == 0 && remaining == 0 -> strings.resultNothing
                        sessionFilled == 0 -> strings.resultNoneFilled
                        else -> strings.resultTemplate.format(sessionFilled, sessionTotal)
                    }
                    coordinator.onFinished(
                        message = message,
                        success = sessionFilled > 0 || remaining == 0,
                    )
                }
            }
            Result.success()
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Title dubbing worker failed", e)
            coordinator.onFinished(message = strings.resultFailed, success = false)
            Result.failure()
        }
    }

    /**
     * AI-перевод с backoff на 429. Короткую паузу (≤ [MAX_INLINE_BACKOFF_MS]) отсиживаем прямо в
     * прогоне и повторяем ту же запись; длинную — возвращаем [AiTitleTranslationUseCase.Outcome.RateLimited],
     * чтобы воркер корректно завершил прогон и продолжил позже, не блокируя WorkManager надолго.
     */
    private suspend fun translateWithBackoff(anime: Anime): AiTitleTranslationUseCase.Outcome {
        var attempt = 0
        while (true) {
            val outcome = aiTitleTranslationUseCase.translate(anime)
            if (outcome !is AiTitleTranslationUseCase.Outcome.RateLimited) return outcome
            attempt++
            val wait = outcome.retryAfterMs.coerceAtLeast(MIN_BACKOFF_MS) + BACKOFF_BUFFER_MS
            if (isStopped || attempt > MAX_INLINE_RETRIES || wait > MAX_INLINE_BACKOFF_MS) return outcome
            Log.i(TAG, "Rate limited on «${anime.title}», backing off ${wait}ms (attempt $attempt)")
            delay(wait)
        }
    }

    companion object {
        private const val TAG = "TitleDubbingWorker"
        private const val BATCH_SIZE = 20
        private const val MAX_PER_RUN = 100
        private const val THROTTLE_MS = 600L
        private const val CONTINUATION_DELAY_MS = 1_500L

        // Backoff на rate limit (429).
        private const val MAX_INLINE_RETRIES = 2
        private const val MIN_BACKOFF_MS = 1_000L
        private const val BACKOFF_BUFFER_MS = 500L
        /** Дольше — не отсиживаем в прогоне, а завершаем и продолжаем отдельным заходом. */
        private const val MAX_INLINE_BACKOFF_MS = 20_000L
        /** Верхняя граница задержки продолжения при исчерпании квоты (напр. дневной лимит). */
        private const val MAX_CONTINUATION_DELAY_MS = 5 * 60 * 1000L
    }
}
