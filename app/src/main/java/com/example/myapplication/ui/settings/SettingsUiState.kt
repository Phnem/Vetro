package com.example.myapplication.ui.settings

import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.data.models.AppTheme
import com.example.myapplication.data.models.AppUpdateStatus

/**
 * Immutable UI state для SettingsScreen
 */
data class SettingsUiState(
    val language: AppLanguage = AppLanguage.EN,
    val theme: AppTheme = AppTheme.SYSTEM,
    val contentType: AppContentType = AppContentType.ANIME,
    val devMirrorDbToDocuments: Boolean = false,
    val devHideShareButton: Boolean = false,
    val devFpsOverlay: Boolean = false,
    /**
     * Автопропуск сегментов в плеере. Один флаг на все виды ([SkipKind]: опенинг, эндинг, рекап) —
     * ровно так его и трактуют оба плеера.
     */
    val autoSkipSegments: Boolean = false,
    /** Включать следующую серию по концу текущей. По умолчанию ВКЛ — см. `AUTO_NEXT_KEY`. */
    val autoNextEpisode: Boolean = true,
    val devAdaptiveGlassScroll: Boolean = false,
    /** Классический интерфейс вместо нового (по умолчанию — новый), см. `DevPreferencesKeys.LEGACY_UI`. */
    val devLegacyUi: Boolean = false,
    /** Карточки с обложкой на всю площадь, см. `DevPreferencesKeys.FULL_BLEED_CARDS`. */
    val devFullBleedCards: Boolean = true,
    val devGithubUpdatesEnabled: Boolean = false,
    val isExportingLogs: Boolean = false,
    val isExportingPdf: Boolean = false,
    val isImportingDb: Boolean = false,
    val importDbMessage: String? = null,
    val isRepairingDb: Boolean = false,
    val repairDbMessage: String? = null,
    val showRepairDbLogDialog: Boolean = false,
    val isTitleDubbing: Boolean = false,
    val titleDubbingProcessed: Int = 0,
    val titleDubbingTotal: Int = 0,
    val titleDubbingMessage: String? = null,
    val showTitleDubbingNoAiDialog: Boolean = false,
    /** Collection Enrichment → Live Maintenance тумблер (по умолчанию ВКЛ). */
    val liveMaintenanceEnabled: Boolean = true,
    /** != null → показать незакрываемый диалог «слишком много пропусков» (значение = число записей). */
    val fullEnrichmentPromptGapCount: Int? = null,
    val updateStatus: AppUpdateStatus = AppUpdateStatus.IDLE,
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val latestDownloadUrl: String? = null,
    val updateChangelogMarkdown: String? = null,
    val isUpdateChangelogLoading: Boolean = false,
    val updateChangelogError: String? = null,
    val latestApkSizeBytes: Long? = null,
    val isApkDownloading: Boolean = false,
    val apkDownloadProgress: Float = 0f,
    val pendingApkPathForInstall: String? = null,
    val latestReleaseHtmlUrl: String? = null,
) {
    /** Новый интерфейс (рабочая область, капсульный док, матовое стекло) — режим по умолчанию. */
    val modernUi: Boolean get() = !devLegacyUi
}
