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
import com.example.myapplication.data.models.AppUpdateStatus
import com.example.myapplication.data.models.toUiStatus
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
private const val UPDATE_APK_NAME = "vetro-update.apk"
const val FDROID_UPDATE_WEBSITE_URL = "https://phnem.github.io/Vetro-Studio/collection"

private data class SettingsTransientState(
    val isUpdateChangelogLoading: Boolean = false,
    val updateChangelogError: String? = null,
    /** Package [versionName] for UI; not persisted. */
    val currentVersionDisplay: String = "",
    val updateSheetShownFromSettingsThisSession: Boolean = false,
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
    val isApkDownloading: Boolean = false,
    val apkDownloadProgress: Float = 0f,
    val pendingApkPathForInstall: String? = null,
)

private fun mergeSettingsUi(
    prefs: Preferences,
    snap: AppUpdateSnapshot,
    t: SettingsTransientState,
): SettingsUiState {
    val githubUpdatesEnabled = prefs[DevPreferencesKeys.GITHUB_UPDATES_ENABLED] == true
    val updateStatus =
        if (!githubUpdatesEnabled) AppUpdateStatus.IDLE
        else if (t.isUpdateChangelogLoading) AppUpdateStatus.LOADING
        else snap.persistedKind.toUiStatus()

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
        devGithubUpdatesEnabled = githubUpdatesEnabled,
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
        updateStatus = updateStatus,
        currentVersion = t.currentVersionDisplay,
        latestVersion = snap.latestTag,
        latestDownloadUrl = snap.latestDownloadUrl,
        updateChangelogMarkdown = snap.updateChangelogMarkdown,
        isUpdateChangelogLoading = t.isUpdateChangelogLoading,
        updateChangelogError = t.updateChangelogError,
        latestApkSizeBytes = snap.latestApkSizeBytes,
        isApkDownloading = t.isApkDownloading,
        apkDownloadProgress = t.apkDownloadProgress,
        pendingApkPathForInstall = t.pendingApkPathForInstall,
        latestReleaseHtmlUrl = snap.latestHtmlUrl,
    )
}

class SettingsViewModel(
    private val repository: AnimeRepository,
    private val appUpdateRepository: AppUpdateRepository,
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
        appUpdateRepository.appUpdateSnapshot,
        _transient,
    ) { prefs, snap, tr -> mergeSettingsUi(prefs, snap, tr) }
        // Подписка на файл настроек живёт, пока экран на виду (+5 с на поворот): в фоне её
        // будила каждая запись прогресса плеера. Последнее значение stateIn сохраняет.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** When true, [MainActivity] may show the global update sheet (not on splash, not deduped by settings). */
    val startupUpdateOverlayEligible: StateFlow<Boolean> = combine(
        appUpdateRepository.appUpdateSnapshot,
        settingsDataStore.data,
        _transient,
    ) { snap, prefs, tr ->
        val githubEnabled = prefs[DevPreferencesKeys.GITHUB_UPDATES_ENABLED] == true
        githubEnabled && snap.startupOverlayEligible && !tr.updateSheetShownFromSettingsThisSession
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var downloadReceiverRegistered = false
    private var activeDownloadId: Long = -1L
    private var progressJob: Job? = null

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id != activeDownloadId || id == -1L) return
            val ctx = context ?: return
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    onDownloadSuccessful(ctx)
                } else {
                    onDownloadFailedCleanup()
                }
            }
        }
    }

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

    fun notifyUpdateChangelogSheetPresentedFromSettings() {
        _transient.update { it.copy(updateSheetShownFromSettingsThisSession = true) }
    }

    fun dismissStartupUpdateOverlayPersisted() {
        viewModelScope.launch {
            appUpdateRepository.dismissStartupOverlayForCurrentRelease()
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

    /** Три прежних флага интерфейса больше ничего не значат — вычищаем их из настроек. */
    private fun dropRetiredUiFlags() {
        viewModelScope.launch {
            settingsDataStore.edit { prefs ->
                DevPreferencesKeys.RETIRED_UI_FLAGS.forEach { key -> prefs.remove(key) }
            }
        }
    }

    fun setDevGithubUpdatesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.edit { it[DevPreferencesKeys.GITHUB_UPDATES_ENABLED] = enabled }
        }
    }

    fun openFdroidUpdateWebsite(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_UPDATE_WEBSITE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private suspend fun ensureCurrentVersionFromPackage(context: Context) {
        if (_transient.value.currentVersionDisplay.isNotEmpty()) return
        val v = runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "v1.0.0"
        }.getOrElse { "v1.0.0" }
        _transient.update { it.copy(currentVersionDisplay = v) }
    }

    fun loadUpdateChangelog(context: Context) {
        if (_transient.value.isUpdateChangelogLoading) return
        viewModelScope.launch {
            val githubEnabled =
                settingsDataStore.data.first()[DevPreferencesKeys.GITHUB_UPDATES_ENABLED] == true
            if (!githubEnabled) return@launch
            ensureCurrentVersionFromPackage(context)
            val lang = AppLanguagePrefs.current(settingsDataStore)
            val strings = getStrings(lang)
            _transient.update {
                it.copy(
                    isUpdateChangelogLoading = true,
                    updateChangelogError = null,
                )
            }
            val ok = appUpdateRepository.refreshAppUpdate(force = true)
            _transient.update {
                it.copy(
                    isUpdateChangelogLoading = false,
                    updateChangelogError = if (!ok) {
                        strings.updateChangelogLoadError
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun startApkDownload(context: Context) {
        val url = uiState.value.latestDownloadUrl?.takeIf { it.isNotBlank() } ?: run {
            openLatestReleaseInBrowser(context)
            return
        }
        val appCtx = context.applicationContext
        val dir = appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        dir.mkdirs()
        val target = File(dir, UPDATE_APK_NAME)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Vetro")
            setDestinationInExternalFilesDir(appCtx, Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_NAME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        progressJob?.cancel()
        unregisterDownloadReceiver()
        activeDownloadId = dm.enqueue(request)
        registerDownloadReceiver(appCtx)
        _transient.update { it.copy(isApkDownloading = true, apkDownloadProgress = 0f) }
        trackDownloadProgress(appCtx, activeDownloadId)
    }

    fun openLatestReleaseInBrowser(context: Context) {
        val apkUrl = uiState.value.latestDownloadUrl?.takeIf { it.isNotBlank() }
        val page = uiState.value.latestReleaseHtmlUrl?.takeIf { it.isNotBlank() }
            ?: "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases"
        val target = apkUrl ?: page
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun manageUnknownAppSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    fun onReturnedFromInstallSettings(context: Context) {
        val path = _transient.value.pendingApkPathForInstall ?: return
        val file = File(path)
        if (!file.exists()) {
            _transient.update { it.copy(pendingApkPathForInstall = null) }
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return
        }
        if (launchPackageInstaller(context, file)) {
            _transient.update { it.copy(pendingApkPathForInstall = null) }
        }
    }

    private fun registerDownloadReceiver(appCtx: Context) {
        if (downloadReceiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appCtx.registerReceiver(downloadCompleteReceiver, filter)
        }
        downloadReceiverRegistered = true
    }

    private fun unregisterDownloadReceiver() {
        if (!downloadReceiverRegistered) return
        runCatching { app.unregisterReceiver(downloadCompleteReceiver) }
        downloadReceiverRegistered = false
    }

    private fun trackDownloadProgress(appCtx: Context, downloadId: Long) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var lastPercent = -1
            while (isActive) {
                // Запрос к провайдеру загрузок — это IPC и курсор, главному потоку он ни к чему.
                val poll = withContext(Dispatchers.IO) { queryApkDownload(dm, downloadId) }
                when (poll) {
                    // Провайдер не ответил курсором — как и раньше, спрашиваем снова.
                    null -> Unit
                    ApkDownloadPoll.Gone, ApkDownloadPoll.Succeeded -> return@launch
                    ApkDownloadPoll.Failed -> {
                        onDownloadFailedCleanup()
                        return@launch
                    }
                    is ApkDownloadPoll.Running -> {
                        // Стейт настроек обновляется только при смене процента, а не 4 раза в
                        // секунду одним и тем же значением.
                        val percent = (poll.fraction * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _transient.update { it.copy(apkDownloadProgress = poll.fraction) }
                        }
                    }
                }
                delay(250)
            }
        }
    }

    private fun onDownloadSuccessful(ctx: Context) {
        progressJob?.cancel()
        progressJob = null
        unregisterDownloadReceiver()
        activeDownloadId = -1L
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val file = dir?.let { File(it, UPDATE_APK_NAME) }
        if (file == null || !file.exists()) {
            onDownloadFailedCleanup()
            return
        }
        _transient.update {
            it.copy(
                isApkDownloading = false,
                apkDownloadProgress = 1f,
                pendingApkPathForInstall = file.absolutePath
            )
        }
        if (launchPackageInstaller(ctx, file)) {
            _transient.update { it.copy(pendingApkPathForInstall = null) }
        }
    }

    private fun onDownloadFailedCleanup() {
        progressJob?.cancel()
        progressJob = null
        unregisterDownloadReceiver()
        activeDownloadId = -1L
        _transient.update { it.copy(isApkDownloading = false, apkDownloadProgress = 0f) }
    }

    private fun launchPackageInstaller(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _transient.update { it.copy(pendingApkPathForInstall = file.absolutePath) }
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            true
        }.getOrElse { e ->
            Log.w(LOG_TAG, "launchPackageInstaller failed", e)
            false
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        unregisterDownloadReceiver()
        super.onCleared()
    }

    fun shareWithDb(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dbFile = context.getDatabasePath("anime.db")
                val shareText = "Check out Vetro — media list manager: https://github.com/Phnem/Vetro-Collection"
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

private sealed interface ApkDownloadPoll {
    data object Gone : ApkDownloadPoll
    data object Succeeded : ApkDownloadPoll
    data object Failed : ApkDownloadPoll
    data class Running(val fraction: Float) : ApkDownloadPoll
}

private fun queryApkDownload(dm: DownloadManager, downloadId: Long): ApkDownloadPoll? =
    dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
        if (!c.moveToFirst()) return@use ApkDownloadPoll.Gone
        when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
            DownloadManager.STATUS_SUCCESSFUL -> ApkDownloadPoll.Succeeded
            DownloadManager.STATUS_FAILED -> ApkDownloadPoll.Failed
            else -> {
                val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                ApkDownloadPoll.Running(
                    if (total > 0L) (soFar.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f,
                )
            }
        }
    }
