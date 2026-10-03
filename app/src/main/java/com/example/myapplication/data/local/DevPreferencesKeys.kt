package com.example.myapplication.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object DevPreferencesKeys {
    val ADAPTIVE_GLASS_SCROLL = booleanPreferencesKey("dev_adaptive_glass_scroll")
    /** When false (default), GitHub release checks and in-app APK updates are disabled (F-Droid mode). */
    val GITHUB_UPDATES_ENABLED = booleanPreferencesKey("dev_github_updates_enabled")

    /**
     * Live Maintenance (фоновое обновление обогащения коллекции). Отсутствие ключа трактуется как ВКЛ —
     * фича включена по умолчанию. См. [com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator].
     */
    val LIVE_MAINTENANCE_ENABLED = booleanPreferencesKey("live_maintenance_enabled")

    /** Фоновый скан нашёл > порога пропусков → показать незакрываемый диалог при открытии настроек. */
    val PENDING_FULL_ENRICHMENT_PROMPT = booleanPreferencesKey("pending_full_enrichment_prompt")
    val PENDING_FULL_ENRICHMENT_GAP_COUNT = intPreferencesKey("pending_full_enrichment_gap_count")

    /**
     * Зеркало домена jut.su: основной домен периодически блокируют, и тогда весь источник нужно
     * переключить на альтернативный хост без пересборки. Пусто/отсутствие ключа = дефолтный
     * «https://jut.su». Значение нормализуется в
     * [com.example.myapplication.media.source.JutSuSource], так что сюда можно писать сырой ввод.
     */
    val JUTSU_MIRROR_DOMAIN = stringPreferencesKey("jutsu_mirror_domain")

    /**
     * Классический интерфейс: прежний док с жидким стеклом и окна вместо страниц. Отсутствие
     * ключа = ВЫКЛ, то есть по умолчанию работает новый интерфейс — рабочая область со свайпом
     * между разделами, капсульный док из матового стекла и морф панелей из дока.
     *
     * Заменил три отдельных dev-флага (навигация свайпом, матовый док, морф панелей): из восьми
     * их сочетаний осмысленных было два.
     */
    val LEGACY_UI = booleanPreferencesKey("dev_legacy_ui")

    /**
     * Экспериментальные карточки коллекции: обложка на всю карточку, текст поверх затемнения.
     * Отсутствие ключа = ВЫКЛ (классическая карточка с постером сбоку).
     */
    val FULL_BLEED_CARDS = booleanPreferencesKey("dev_full_bleed_cards")

    /** Ключи удалённых настроек — стираются из файла настроек при старте (см. [LEGACY_UI]). */
    val RETIRED_UI_FLAGS = listOf(
        booleanPreferencesKey("dev_select_dock_navigation"),
        booleanPreferencesKey("dev_glass_capsule_dock"),
        booleanPreferencesKey("dev_staged_sheet_motion"),
        // v3.3.5: промо V3.3.3, флаг старого медиа-движка и метка дубляжа — код, читавший их, удалён.
        booleanPreferencesKey("temp_player_promo_v333_dismissed"),
        booleanPreferencesKey("use_native_media_engine"),
        booleanPreferencesKey("title_dubbing_ever_enabled"),
    )
}
