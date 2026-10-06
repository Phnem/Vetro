package com.example.myapplication.ui.settings

import com.example.myapplication.localplayer.ui.PlayerSettingsKeys
import com.example.myapplication.data.local.AppLanguagePrefs
import com.example.myapplication.data.local.AppThemePrefs
import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.data.models.AppTheme
import com.example.myapplication.data.models.AppUpdateSnapshot
import com.phnem.vetro.BuildConfig
import com.example.myapplication.data.local.CollectionPdfGenerator
import com.example.myapplication.data.local.DevPreferencesKeys
import com.example.myapplication.data.local.SQLDelightDatabaseFactory
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.data.repository.AppUpdateRepository
import com.example.myapplication.domain.settings.ImportAnimeDbUseCase
import com.example.myapplication.domain.settings.RepairDbCoordinator
import com.example.myapplication.domain.settings.RepairDbState
import com.example.myapplication.domain.titles.TitleDubbingCoordinator
import com.example.myapplication.domain.titles.TitleDubbingState
import com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.utils.getDevRepairDbStrings
import com.example.myapplication.utils.getStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val KEY_CONTENT_TYPE = stringPreferencesKey("contentType")
private val KEY_DEV_MIRROR_DB = booleanPreferencesKey("dev_mirror_db_to_documents")
private val KEY_DEV_HIDE_SHARE = booleanPreferencesKey("dev_hide_share_button")
private val KEY_DEV_FPS_OVERLAY = booleanPreferencesKey("dev_fps_overlay")
private const val LOG_TAG = "SettingsViewModel"

private data class SettingsTransientState(
    /** Package [versionName] for UI; not persisted. */
    val currentVersionDisplay: String = "",
    val isExportingLogs: Boolean = false,
    val isExportingPdf: Boolean = false,
    val isImportingDb: Boolean = false,
    val importDbMessage: String? = null,
    val isRepairingDb: Boolean = false,
    val repairDbMessage: String? = null,
    val showRepairDbLogDialog: Boolean = false,
    val pendingRepairDbLog: String? = null,
    val isTitleDubbing: Boolean = false,
    val titleDubbingProcessed: Int = 0,
    val titleDubbingTotal: Int = 0,
    val titleDubbingMessage: String? = null,
    val showTitleDubbingNoAiDialog: Boolean = false,
    val fullEnrichmentPromptGapCount: Int? = null,
)

private fun mergeSettingsUi(
    prefs: Preferences,
    t: SettingsTransientState,
): SettingsUiState {
    return SettingsUiState(
        language = AppLanguagePrefs.from(prefs),
        theme = AppThemePrefs.from(prefs),
        contentType = runCatching { AppContentType.valueOf(prefs[KEY_CONTENT_TYPE] ?: "ANIME") }.getOrElse { AppContentType.ANIME },
        devMirrorDbToDocuments = prefs[KEY_DEV_MIRROR_DB] ?: false,
        devHideShareButton = prefs[KEY_DEV_HIDE_SHARE] ?: false,
        devFpsOverlay = prefs[KEY_DEV_FPS_OVERLAY] ?: false,
        autoSkipSegments = prefs[PlayerSettingsKeys.AUTO_SKIP] ?: false,
        autoNextEpisode = prefs[PlayerSettingsKeys.AUTO_NEXT] ?: true,
        devAdaptiveGlassScroll = prefs[DevPreferencesKeys.ADAPTIVE_GLASS_SCROLL] ?: false,
        devLegacyUi = prefs[DevPreferencesKeys.LEGACY_UI] ?: false,
        devFullBleedCards = prefs[DevPreferencesKeys.FULL_BLEED_CARDS] ?: true,
        isExportingLogs = t.isExportingLogs,
        isExportingPdf = t.isExportingPdf,
        isImportingDb = t.isImportingDb,
        importDbMessage = t.importDbMessage,
        isRepairingDb = t.isRepairingDb,
        repairDbMessage = t.repairDbMessage,
        showRepairDbLogDialog = t.showRepairDbLogDialog,
        isTitleDubbing = t.isTitleDubbing,
        titleDubbingProcessed = t.titleDubbingProcessed,
        titleDubbingTotal = t.titleDubbingTotal,
        titleDubbingMessage = t.titleDubbingMessage,
        showTitleDubbingNoAiDialog = t.showTitleDubbingNoAiDialog,
        liveMaintenanceEnabled = prefs[DevPreferencesKeys.LIVE_MAINTENANCE_ENABLED] ?: true,
        fullEnrichmentPromptGapCount = t.fullEnrichmentPromptGapCount,
        currentVersion = t.currentVersionDisplay,
    )
}

class SettingsViewModel(
    private val repository: AnimeRepository,
    private val settingsDataStore: DataStore<Preferences>,
    private val databaseFactory: SQLDelightDatabaseFactory,
    private val importAnimeDbUseCase: ImportAnimeDbUseCase,
    private val repairDbCoordinator: RepairDbCoordinator,
    private val collectionPdfGenerator: CollectionPdfGenerator,
    private val titleDubbingCoordinator: TitleDubbingCoordinator,
    private val enrichmentCoordinator: CollectionEnrichmentCoordinator,
    private val aiCredentialsStore: AiCredentialsStore,
    private val app: Application
) : ViewModel() {

    private val _transient = MutableStateFlow(SettingsTransientState())

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsDataStore.data,
        _transient,
    ) { prefs, tr -> mergeSettingsUi(prefs, tr) }
        // Подписка на файл настроек живёт, пока экран на виду (+5 с на поворот): в фоне её
        // будила каждая запись прогресса плеера. Последнее значение stateIn сохраняет.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        dropRetiredUiFlags()
        viewModelScope.launch {
            val v = runCatching {
                val pInfo = app.packageManager.getPackageInfo(app.packageName, 0)
                pInfo.versionName ?: "v1.0.0"
            }.getOrElse { "v1.0.0" }
            _transient.update { it.copy(currentVersionDisplay = v) }
        }

        // Фоновое «Исправление БД» живёт в RepairDbCoordinator (WorkManager) —
        // экран настроек лишь отражает его состояние, даже если открыт заново.
        viewModelScope.launch {
            repairDbCoordinator.state.collect { state ->
                when (state) {
                    is RepairDbState.Idle -> _transient.update {
                        it.copy(isRepairingDb = false)
                    }
                    is RepairDbState.Running -> _transient.update {
                        it.copy(isRepairingDb = true, repairDbMessage = null)
                    }
                    // Фаза «поля» завершилась — просто гасим индикатор; в слитом «Полном обогащении»
                    // сразу стартует фаза «названия» (лог-диалог дев-режима больше не показываем).
                    is RepairDbState.Finished -> _transient.update {
                        it.copy(isRepairingDb = false)
                    }
                }
            }
        }

        // Порог перегрузки: фоновый скан просит запустить полное обогащение (незакрываемый диалог).
        viewModelScope.launch {
            enrichmentCoordinator.prompt.collect { prompt ->
                _transient.update { it.copy(fullEnrichmentPromptGapCount = prompt?.gapCount) }
            }
        }

        // Фоновый «Дубляж названий» — экран лишь отражает состояние координатора.
        viewModelScope.launch {
            titleDubbingCoordinator.state.collect { state ->
                when (state) {
                    is TitleDubbingState.Idle -> _transient.update {
                        it.copy(isTitleDubbing = false)
                    }
                    is TitleDubbingState.Running -> _transient.update {
                        it.copy(
                            isTitleDubbing = true,
                            titleDubbingProcessed = state.processed,
                            titleDubbingTotal = state.total,
                            titleDubbingMessage = null,
                        )
                    }
                    is TitleDubbingState.Finished -> _transient.update {
                        it.copy(isTitleDubbing = false, titleDubbingMessage = state.message)
                    }
                }
            }
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsDataStore.edit { it[AppLanguagePrefs.KEY] = language.name }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsDataStore.edit { it[AppThemePrefs.KEY] = theme.name }
        }
    }

    fun setContentType(contentType: AppContentType) {
        viewModelScope.launch {
            settingsDataStore.edit { it[KEY_CONTENT_TYPE] = contentType.name }
        }
    }

    fun setDevMirrorDb(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[KEY_DEV_MIRROR_DB] = enabled }
        }
    }

    fun setDevHideShare(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[KEY_DEV_HIDE_SHARE] = enabled }
        }
    }

    /**
     * Единственный писатель [PlayerSettingsKeys.AUTO_SKIP].
     *
     * Ключ берётся из [PlayerSettingsKeys], а не объявляется здесь второй строкой: его читают
     * оба плеера (`StreamPlayerActivity`, `DownloadedPlayerActivity`), и разъехавшееся имя
     * оставило бы автопропуск недостижимым — с переключателем, который внешне работает.
     */
    fun setAutoSkipSegments(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[PlayerSettingsKeys.AUTO_SKIP] = enabled }
        }
    }

    /** Единственный писатель [PlayerSettingsKeys.AUTO_NEXT] — по тем же причинам. */
    fun setAutoNextEpisode(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[PlayerSettingsKeys.AUTO_NEXT] = enabled }
        }
    }

    fun setDevFpsOverlay(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[KEY_DEV_FPS_OVERLAY] = enabled }
        }
    }

    fun setDevAdaptiveGlassScroll(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[DevPreferencesKeys.ADAPTIVE_GLASS_SCROLL] = enabled }
        }
    }

    /**
     * Переключение классического интерфейса. [onApplied] вызывается после записи — экран
     * пересоздаёт активити: смена режима меняет структуру дерева над `layerBackdrop`, и живое
     * переключение могло бы на кадр оставить стекло плоским.
     */
    fun setDevLegacyUi(enabled: Boolean, onApplied: () -> Unit) {
        viewModelScope.launch {
            settingsDataStore.edit { it[DevPreferencesKeys.LEGACY_UI] = enabled }
            onApplied()
        }
    }

    fun setDevFullBleedCards(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[DevPreferencesKeys.FULL_BLEED_CARDS] = enabled }
        }
    }

    /** Три прежних флага интерфейса больше ничего не значат — вычищаем их из настроек. */
    private fun dropRetiredUiFlags() {
        viewModelScope.launch {
            settingsDataStore.edit { prefs ->
                DevPreferencesKeys.RETIRED_UI_FLAGS.forEach { key -> prefs.remove(key) }
            }
        }
    }

    fun shareWithDb(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dbFile = context.getDatabasePath("anime.db")
                val shareText = "Check out Vetro — media list manager: https://github.com/Phnem/Vetro"
                val sendIntent = if (dbFile.exists()) {
                    databaseFactory.checkpoint()
                    val exportDir = File(context.cacheDir, "share").apply { mkdirs() }
                    val exportFile = File(exportDir, "vetro_list.db")
                    dbFile.copyTo(exportFile, overwrite = true)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        exportFile
                    )
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "text/plain"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        type = "text/plain"
                    }
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(sendIntent, null))
                }
            }
        }
    }

    fun exportLogs(context: Context) {
        if (_transient.value.isExportingLogs) return
        viewModelScope.launch {
            _transient.update { it.copy(isExportingLogs = true) }
            try {
                val logFile = withContext(Dispatchers.IO) {
                    val exportDir = File(context.cacheDir, "share").apply { mkdirs() }
                    val exportFile = File(exportDir, "vetro_logcat.txt")
                    val pid = Process.myPid().toString()
                    val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid", pid).start()
                    val raw = process.inputStream.bufferedReader().use { it.readText() }
                    val logs = filterSystemViewFrameRateSpam(raw)
                    process.waitFor()
                    exportFile.writeText(logs)
                    exportFile
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    logFile
                )
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Vetro logs")
                    type = "text/plain"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, null))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to export logs", e)
            } finally {
                _transient.update { it.copy(isExportingLogs = false) }
            }
        }
    }

    fun exportCollectionPdf(context: Context) {
        if (_transient.value.isExportingPdf) return
        viewModelScope.launch {
            _transient.update { it.copy(isExportingPdf = true) }
            try {
                val strings = getStrings(uiState.value.language)
                val pdfFile = withContext(Dispatchers.IO) {
                    databaseFactory.checkpoint()
                    val items = repository.getAllAnimeSnapshot()
                    val exportDir = File(context.cacheDir, "share").apply { mkdirs() }
                    val file = File(exportDir, "vetro_collection.pdf")
                    collectionPdfGenerator.writeToFile(
                        file = file,
                        items = items,
                        documentTitle = strings.devExportPdfDocumentTitle,
                        columnTitle = strings.devExportPdfColumnTitle,
                        columnEpisodes = strings.devExportPdfColumnEpisodes,
                        columnRating = strings.devExportPdfColumnRating,
                        emptyMessage = strings.devExportPdfEmpty
                    )
                    file
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    pdfFile
                )
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "${strings.devExportPdfDocumentTitle} — Vetro")
                    type = "application/pdf"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, null))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to export PDF", e)
            } finally {
                _transient.update { it.copy(isExportingPdf = false) }
            }
        }
    }

    fun importDbFromFile(context: Context, uri: Uri) {
        if (_transient.value.isImportingDb) return
        val strings = getStrings(uiState.value.language)
        viewModelScope.launch {
            _transient.update { it.copy(isImportingDb = true, importDbMessage = null) }
            val result = importAnimeDbUseCase(context, uri)
            result.fold(
                onSuccess = { summary ->
                    val message = if (summary.addedCount > 0) {
                        strings.devImportDbResultAddedTemplate.format(summary.addedCount)
                    } else {
                        strings.devImportDbResultNoNew
                    }
                    _transient.update { it.copy(isImportingDb = false, importDbMessage = message) }
                },
                onFailure = { error ->
                    Log.w(LOG_TAG, "Failed to import DB", error)
                    _transient.update {
                        it.copy(
                            isImportingDb = false,
                            importDbMessage = strings.devImportDbResultInvalid
                        )
                    }
                }
            )
        }
    }

    fun clearImportDbMessage() {
        _transient.update { it.copy(importDbMessage = null) }
    }

    /**
     * «Нажал и пошёл»: сама проверка выполняется в WorkManager ([RepairDbCoordinator]),
     * можно уходить с экрана и сворачивать приложение — работа доедет до конца.
     * ViewModel лишь отражает состояние координатора (см. коллектор в init).
     */
    fun repairDatabase() {
        if (_transient.value.isRepairingDb) return
        repairDbCoordinator.start(
            language = uiState.value.language,
            contentType = uiState.value.contentType,
        )
    }

    fun discardRepairDbLog() {
        repairDbCoordinator.acknowledgeResult()
        _transient.update {
            it.copy(showRepairDbLogDialog = false, pendingRepairDbLog = null)
        }
    }

    /**
     * «Дубляж названий»: полный перескан. Помечает фичу как включённую (для авто-дубляжа новых
     * тайтлов), затем запускает фоновый проход. Если нет подключённого AI — показываем диалог,
     * но проход всё равно стартует (API-обогащение полезно и без AI).
     */
    fun runTitleDubbing() {
        if (_transient.value.isTitleDubbing) return
        viewModelScope.launch {
            val hasAi = aiCredentialsStore.getAllConnectedProviders().isNotEmpty()
            if (!hasAi) {
                _transient.update { it.copy(showTitleDubbingNoAiDialog = true) }
            }
            titleDubbingCoordinator.start(uiState.value.language, fullRescan = true)
        }
    }

    fun dismissTitleDubbingNoAiDialog() {
        _transient.update { it.copy(showTitleDubbingNoAiDialog = false) }
    }

    fun clearTitleDubbingMessage() {
        titleDubbingCoordinator.acknowledgeResult()
        _transient.update { it.copy(titleDubbingMessage = null) }
    }

    // ==========================================================
    // Обогащение коллекции (Collection Enrichment)
    // ==========================================================

    /** Модуль 1: полный прогон (поля → названия). Прогресс отражают isRepairingDb / isTitleDubbing. */
    fun runFullEnrichment() {
        if (_transient.value.isRepairingDb || _transient.value.isTitleDubbing) return
        enrichmentCoordinator.startFullEnrichment(
            language = uiState.value.language,
            contentType = uiState.value.contentType,
        )
    }

    /** Модуль 2: тумблер Live Maintenance (вкл/выкл + планирование). */
    fun setLiveMaintenance(enabled: Boolean) {
        enrichmentCoordinator.setLiveMaintenanceEnabled(enabled)
    }

    /** Незакрываемый диалог «слишком много пропусков» → «Начать»: запускаем полное обогащение. */
    fun confirmFullEnrichmentPrompt() {
        _transient.update { it.copy(fullEnrichmentPromptGapCount = null) }
        enrichmentCoordinator.startFullEnrichment(
            language = uiState.value.language,
            contentType = uiState.value.contentType,
        )
    }

    /** Диалог → «Отмена»: скрываем и выключаем Live Maintenance. */
    fun cancelFullEnrichmentPrompt() {
        _transient.update { it.copy(fullEnrichmentPromptGapCount = null) }
        enrichmentCoordinator.dismissFullEnrichmentPrompt()
        enrichmentCoordinator.setLiveMaintenanceEnabled(false)
    }

    fun exportRepairDbLog(context: Context) {
        val logText = _transient.value.pendingRepairDbLog ?: run {
            discardRepairDbLog()
            return
        }
        val shareTitle = getDevRepairDbStrings(uiState.value.language).logShareTitle
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val exportDir = File(context.cacheDir, "share").apply { mkdirs() }
                    val exportFile = File(exportDir, REPAIR_DB_LOG_NAME)
                    exportFile.writeText(logText)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        exportFile,
                    )
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, shareTitle)
                        type = "text/plain"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent.createChooser(sendIntent, shareTitle))
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to export repair DB log", e)
            } finally {
                discardRepairDbLog()
            }
        }
    }

    fun clearRepairDbMessage() {
        _transient.update { it.copy(repairDbMessage = null) }
    }
}

private const val REPAIR_DB_LOG_NAME = "vetro_repair_db_log.txt"

/** Android 15+ / Compose: system `View` INFO lines for setRequestedFrameRate(NaN). */
private fun filterSystemViewFrameRateSpam(log: String): String =
    log.lineSequence()
        .filterNot { line ->
            "setRequestedFrameRate" in line && "frameRate=NaN" in line
        }
        .joinToString("\n")
