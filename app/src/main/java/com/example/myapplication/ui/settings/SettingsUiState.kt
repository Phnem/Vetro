package com.example.myapplication.ui.settings

import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.data.models.AppTheme

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
    /** «Порог доступности»: пуш о серии ждёт озвучек. По умолчанию ВЫКЛ, см. `AvailabilityThresholdPrefs`. */
    val availabilityThreshold: Boolean = false,
    val devAdaptiveGlassScroll: Boolean = false,
    /** Карточки с обложкой на всю площадь, см. `DevPreferencesKeys.FULL_BLEED_CARDS`. */
    val devFullBleedCards: Boolean = true,
    val devExperimentalGrid: Boolean = true,
    /** Старая заставка («переливание») вместо новой анимации логотипа, см. `DevPreferencesKeys.LEGACY_SPLASH`. */
    val devLegacySplash: Boolean = false,
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
    /** != null → показать незакрываемый диалог «слишком много пропусков» (значение = число записей). */
    val fullEnrichmentPromptGapCount: Int? = null,
    val currentVersion: String = "",
) {
    /**
     * Новый интерфейс (рабочая область, капсульный док, матовое стекло). С v3.4.9 — единственный:
     * dev-флаг классического интерфейса убран, ключ стирается при старте (`RETIRED_UI_FLAGS`).
     */
    val modernUi: Boolean get() = true

    /** Анимация «назад» как в exteraGram. С v3.4.9 всегда включена, переключатель убран. */
    val devExteraBack: Boolean get() = true
}
