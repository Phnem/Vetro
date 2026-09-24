package com.example.myapplication.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object DevPreferencesKeys {
    val ADAPTIVE_GLASS_SCROLL = booleanPreferencesKey("dev_adaptive_glass_scroll")
    /** When false (default), GitHub release checks and in-app APK updates are disabled (F-Droid mode). */
    val GITHUB_UPDATES_ENABLED = booleanPreferencesKey("dev_github_updates_enabled")

    /** Once the user has ever run «Дубляж названий», new titles are auto-enriched in the background. */
    val TITLE_DUBBING_EVER_ENABLED = booleanPreferencesKey("title_dubbing_ever_enabled")

    /**
     * Live Maintenance (фоновое обновление обогащения коллекции). Отсутствие ключа трактуется как ВКЛ —
     * фича включена по умолчанию. См. [com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator].
     */
    val LIVE_MAINTENANCE_ENABLED = booleanPreferencesKey("live_maintenance_enabled")

    /** Фоновый скан нашёл > порога пропусков → показать незакрываемый диалог при открытии настроек. */
    val PENDING_FULL_ENRICHMENT_PROMPT = booleanPreferencesKey("pending_full_enrichment_prompt")
    val PENDING_FULL_ENRICHMENT_GAP_COUNT = intPreferencesKey("pending_full_enrichment_gap_count")

    /**
     * Native Media Engine (AniLibria/AnimeGo Kotlin sources + ffmpeg remux).
     * Absence = ON (default). When false, resolveHosters returns empty and UI falls back
     * to legacy Python download wizard only.
     */
    val USE_NATIVE_MEDIA_ENGINE = booleanPreferencesKey("use_native_media_engine")
    /**
     * Зеркало домена jut.su: основной домен периодически блокируют, и тогда весь источник нужно
     * переключить на альтернативный хост без пересборки. Пусто/отсутствие ключа = дефолтный
     * «https://jut.su». Значение нормализуется в
     * [com.example.myapplication.media.source.JutSuSource], так что сюда можно писать сырой ввод.
     */
    val JUTSU_MIRROR_DOMAIN = stringPreferencesKey("jutsu_mirror_domain")

    /** TEMP V3.3.3 promo. Delete this key together with PlayerPowerPromoDialog. */
    val TEMP_PLAYER_PROMO_V333_DISMISSED = booleanPreferencesKey("temp_player_promo_v333_dismissed")

    /**
     * Классический интерфейс: прежний док с жидким стеклом и окна вместо страниц. Отсутствие
     * ключа = ВЫКЛ, то есть по умолчанию работает новый интерфейс — рабочая область со свайпом
     * между разделами, капсульный док из матового стекла и морф панелей из дока.
     *
     * Заменил три отдельных dev-флага (навигация свайпом, матовый док, морф панелей): из восьми
     * их сочетаний осмысленных было два.
     */
    val LEGACY_UI = booleanPreferencesKey("dev_legacy_ui")

    /** Ключи трёх прежних флагов — удаляются из настроек один раз, см. [LEGACY_UI]. */
    val RETIRED_UI_FLAGS = listOf(
        booleanPreferencesKey("dev_select_dock_navigation"),
        booleanPreferencesKey("dev_glass_capsule_dock"),
        booleanPreferencesKey("dev_staged_sheet_motion"),
    )
}
