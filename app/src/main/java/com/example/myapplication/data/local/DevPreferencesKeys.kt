package com.example.myapplication.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object DevPreferencesKeys {
    val ADAPTIVE_GLASS_SCROLL = booleanPreferencesKey("dev_adaptive_glass_scroll")

    /** Разовая проверка записей, привязанных к чужому тайтлу (см. RepairAnimeDbUseCase.auditForeignMatches). */
    val FOREIGN_MATCH_AUDIT_DONE = booleanPreferencesKey("foreign_match_audit_v1_done")

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
     * Экспериментальные карточки коллекции: обложка на всю карточку, текст поверх затемнения.
     * Отсутствие ключа = ВЫКЛ (классическая карточка с постером сбоку).
     */
    val FULL_BLEED_CARDS = booleanPreferencesKey("dev_full_bleed_cards")

    /** Избранные в горизонтальной ленте над коллекцией. Отсутствие ключа = ВЫКЛ. */
    val EXPERIMENTAL_GRID = booleanPreferencesKey("dev_experimental_grid")

    /**
     * Старая заставка старта («переливание»: волна и слово шрифтом Asgrike) вместо анимации логотипа,
     * где «Vetro» схлопывается в знак. Отсутствие ключа = ВЫКЛ, то есть идёт новая. См. `ui/splash/LogoSplash.kt`.
     */
    val LEGACY_SPLASH = booleanPreferencesKey("dev_legacy_splash")

    /**
     * versionCode, с которым заставка последний раз стартовала. Совпадает с текущим — это повторный
     * запуск той же сборки, и заставка идёт вдвое быстрее; нет ключа или другая сборка — первый
     * запуск после установки или обновления, заставка целиком. См. `ui/navigation/StartupSplash.kt`.
     */
    val SPLASH_SEEN_VERSION = intPreferencesKey("splash_seen_version_code")

    /** Ключи удалённых настроек — стираются из файла настроек при старте. */
    val RETIRED_UI_FLAGS = listOf(
        booleanPreferencesKey("dev_select_dock_navigation"),
        booleanPreferencesKey("dev_glass_capsule_dock"),
        booleanPreferencesKey("dev_staged_sheet_motion"),
        // v3.3.5: промо V3.3.3, флаг старого медиа-движка и метка дубляжа — код, читавший их, удалён.
        booleanPreferencesKey("temp_player_promo_v333_dismissed"),
        booleanPreferencesKey("use_native_media_engine"),
        booleanPreferencesKey("title_dubbing_ever_enabled"),
        // v3.3.8: переключатель «Обновления через GitHub» убран, режим решает подпись приложения.
        booleanPreferencesKey("dev_github_updates_enabled"),
        // v3.4.3: новый вид bottom sheet стал постоянным, dev-переключатель убран.
        booleanPreferencesKey("dev_sheet_style_test"),
        // Эксперимент «ползунки как чипы» отменён.
        booleanPreferencesKey("dev_slider_band_style"),
        // v3.4.6: тумблер фонового обогащения убран, оно работает всегда; выключенное раньше — стирается.
        booleanPreferencesKey("live_maintenance_enabled"),
        // v3.4.9: новый интерфейс и анимация «назад» влиты в основу, их dev-переключатели убраны.
        booleanPreferencesKey("dev_legacy_ui"),
        booleanPreferencesKey("dev_extera_back"),
    )
}
