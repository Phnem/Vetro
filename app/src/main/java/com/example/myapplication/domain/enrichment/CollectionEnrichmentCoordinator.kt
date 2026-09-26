package com.example.myapplication.domain.enrichment

import com.example.myapplication.domain.BackgroundSchedule
import com.example.myapplication.AppScope
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.myapplication.data.local.DevPreferencesKeys
import com.example.myapplication.domain.enrichment.weblinks.WebLinkEnrichmentWorker
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Незакрываемый диалог «слишком много пропусков — сделайте полное обогащение». */
data class FullEnrichmentPrompt(val gapCount: Int)

/**
 * Оркестратор фичи «Обогащение коллекции». Прогресс полного прогона отображается через уже
 * существующие состояния [com.example.myapplication.domain.settings.RepairDbCoordinator] (фаза полей)
 * и [com.example.myapplication.domain.titles.TitleDubbingCoordinator] (фаза названий) — их слушает
 * SettingsViewModel. Этот координатор отвечает за: запуск полного прогона, вкл/выкл и планирование
 * Live Maintenance, и сигнал «нужно полное обогащение» (порог перегрузки).
 */
class CollectionEnrichmentCoordinator(
    private val context: Context,
    private val settingsDataStore: DataStore<Preferences>,
    appScope: AppScope,
) {
    // Общая область процесса (AppScope) вместо своей CoroutineScope(SupervisorJob() + IO).
    private val scope: CoroutineScope = appScope
    private val interactivePauseLock = Any()
    @Volatile
    private var webLinkEnrichmentPaused = false
    private val interactiveMediaPauseGate = InteractiveMediaPauseGate(
        onFirstAcquire = ::pauseAutomaticWebLinkEnrichment,
        onLastRelease = ::resumeAutomaticWebLinkEnrichment,
    )

    private val _prompt = MutableStateFlow<FullEnrichmentPrompt?>(null)
    /** != null → показать незакрываемый диалог. Переживает холодный старт (seed из prefs). */
    val prompt: StateFlow<FullEnrichmentPrompt?> = _prompt.asStateFlow()

    init {
        scope.launch {
            val prefs = runCatching { settingsDataStore.data.first() }.getOrNull() ?: return@launch
            if (prefs[DevPreferencesKeys.PENDING_FULL_ENRICHMENT_PROMPT] == true) {
                _prompt.value = FullEnrichmentPrompt(
                    prefs[DevPreferencesKeys.PENDING_FULL_ENRICHMENT_GAP_COUNT] ?: 0,
                )
            }
        }
    }

    /** Старт приложения: планируем периодику + догон ссылок «Где смотреть». */
    fun ensureScheduled() {
        scope.launch {
            if (isLiveMaintenanceEnabled()) schedulePeriodic()
        }
        enqueueWebLinkEnrichment()
    }

    // ==========================================================
    // «Где смотреть»: независимый воркер резолва ссылок
    // ==========================================================

    /** Догон/старт резолва ссылок. KEEP — не плодим параллельные заходы. */
    fun enqueueWebLinkEnrichment() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<WebLinkEnrichmentWorker>()
            .setConstraints(constraints)
            .build()
        synchronized(interactivePauseLock) {
            if (webLinkEnrichmentPaused) return
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WEB_LINK_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Self-reschedule остатка ссылок. APPEND — после текущего захода. */
    fun scheduleWebLinkContinuation(delayMs: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<WebLinkEnrichmentWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        synchronized(interactivePauseLock) {
            if (webLinkEnrichmentPaused) return
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WEB_LINK_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    /**
     * Pauses only automatic web-link enrichment. Manual full enrichment and downloads remain
     * user-controlled and are intentionally unaffected.
     */
    fun acquireInteractiveMediaPause(): AutoCloseable = interactiveMediaPauseGate.acquire()

    val isWebLinkEnrichmentPaused: Boolean
        get() = webLinkEnrichmentPaused

    private fun pauseAutomaticWebLinkEnrichment() {
        synchronized(interactivePauseLock) {
            webLinkEnrichmentPaused = true
        }
        WorkManager.getInstance(context).cancelUniqueWork(WEB_LINK_WORK)
    }

    private fun resumeAutomaticWebLinkEnrichment() {
        synchronized(interactivePauseLock) {
            if (interactiveMediaPauseGate.hasActiveTokens) return
            webLinkEnrichmentPaused = false
        }
        enqueueWebLinkEnrichment()
    }

    suspend fun isLiveMaintenanceEnabled(): Boolean {
        val prefs = runCatching { settingsDataStore.data.first() }.getOrNull() ?: return true
        // Отсутствие ключа = ВКЛ по умолчанию.
        return prefs[DevPreferencesKeys.LIVE_MAINTENANCE_ENABLED] ?: true
    }

    // ==========================================================
    // Модуль 1: полное обогащение (поля → названия)
    // ==========================================================

    fun startFullEnrichment(language: AppLanguage, contentType: AppContentType) {
        clearPendingPrompt()
        // Ссылки «Где смотреть» — отдельным независимым воркером, параллельно (не фазой этого).
        enqueueWebLinkEnrichment()
        val request = OneTimeWorkRequestBuilder<FullEnrichmentWorker>()
            .setInputData(
                workDataOf(
                    FullEnrichmentWorker.KEY_LANGUAGE to language.name,
                    FullEnrichmentWorker.KEY_CONTENT_TYPE to contentType.name,
                ),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FULL_ENRICHMENT_WORK, ExistingWorkPolicy.KEEP, request)
    }

    // ==========================================================
    // Модуль 2: Live Maintenance (планирование)
    // ==========================================================

    /** Вкл/выкл + (пере)планирование. При включении — немедленный скан (он же выявит перегрузку). */
    fun setLiveMaintenanceEnabled(enabled: Boolean) {
        scope.launch {
            settingsDataStore.edit { it[DevPreferencesKeys.LIVE_MAINTENANCE_ENABLED] = enabled }
            if (enabled) {
                schedulePeriodic()
                enqueueImmediateScan()
            } else {
                cancelPeriodic()
            }
        }
    }

    /** Периодический скан раз в 6 ч (при наличии сети). Idempotent (KEEP). */
    fun schedulePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<LiveMaintenanceWorker>(
            BackgroundSchedule.COLLECTION_MAINTENANCE_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LIVE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(LIVE_PERIODIC_WORK)
    }

    /** Мгновенный догон (при включении тумблера или добавлении записи). KEEP — не дублируем живой. */
    fun enqueueImmediateScan() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<LiveMaintenanceWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(LIVE_ONESHOT_WORK, ExistingWorkPolicy.KEEP, request)
        // Догнать ссылки для только что добавленной записи.
        enqueueWebLinkEnrichment()
    }

    /** Self-reschedule остатка (растягиваем AI-переводы). APPEND — после текущего захода. */
    internal fun scheduleContinuation(delayMs: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<LiveMaintenanceWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(LIVE_ONESHOT_WORK, ExistingWorkPolicy.APPEND, request)
    }

    // ==========================================================
    // Порог перегрузки
    // ==========================================================

    /** Фоновый скан нашёл слишком много пропусков — просим пользователя запустить полный прогон. */
    internal fun requestFullEnrichmentPrompt(gapCount: Int) {
        _prompt.value = FullEnrichmentPrompt(gapCount)
        scope.launch {
            settingsDataStore.edit {
                it[DevPreferencesKeys.PENDING_FULL_ENRICHMENT_PROMPT] = true
                it[DevPreferencesKeys.PENDING_FULL_ENRICHMENT_GAP_COUNT] = gapCount
            }
        }
    }

    fun dismissFullEnrichmentPrompt() {
        clearPendingPrompt()
    }

    private fun clearPendingPrompt() {
        _prompt.value = null
        scope.launch {
            settingsDataStore.edit {
                it.remove(DevPreferencesKeys.PENDING_FULL_ENRICHMENT_PROMPT)
                it.remove(DevPreferencesKeys.PENDING_FULL_ENRICHMENT_GAP_COUNT)
            }
        }
    }

    companion object {
        /** > этого числа записей с пробелами — фон не крутим, просим полное обогащение. */
        const val OVERFLOW_THRESHOLD = 50

        private const val FULL_ENRICHMENT_WORK = "CollectionFullEnrichment"
        private const val LIVE_PERIODIC_WORK = "LiveMaintenancePeriodic"
        private const val LIVE_ONESHOT_WORK = "LiveMaintenanceOneShot"
        private const val WEB_LINK_WORK = "WebLinkEnrichment"
    }
}
