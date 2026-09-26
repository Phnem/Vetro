package com.example.myapplication.network

/**
 * Внешние id результата поиска — единая структура вместо bonus-полей по образцу [malId] на
 * этом же классе (тот паттерн не масштабируется: следующий источник — снова новое поле,
 * снова ветвление по строке [ApiSearchResult.source]). Используется новым MOVIE/SERIES-кодом
 * (TMDB/Kinopoisk); аниме/манга-источники продолжают жить на старых полях
 * [ApiSearchResult.externalId]/[ApiSearchResult.malId] — их миграция сюда вне скоупа этой фичи.
 */
data class ExternalIds(
    val anilist: Int? = null,
    val mal: Int? = null,
    val shikimori: Int? = null,
    val tmdb: Int? = null,
    val kinopoisk: Int? = null,
    /** Canonical IMDb id (`tt0412142`). String: the `tt` prefix and leading zeros are significant. */
    val imdb: String? = null,
    /** TheTVDB id: ключ Fanart.tv (сериалы) и TVmaze. */
    val tvdb: Int? = null,
)

/**
 * Unified model for API search results (Shikimori, AniList, Jikan, TMDB).
 * Used for display in search UI and for adding to local DB.
 */
data class ApiSearchResult(
    val title: String,
    val altTitle: String?,
    val posterUrl: String?,
    val episodes: Int,
    val description: String,
    val type: String,
    val genres: List<String>,
    val rating: Int?,
    val source: String,
    val categoryType: String,
    val externalId: String?,
    /** MAL id, если источник отдал его дополнительно к [externalId] (напр. Shikimori detail → myanimelist_id). */
    val malId: Int? = null,
    /** Тайтл сейчас выходит (онгоинг)? null = источник статус не сообщил. */
    val isOngoing: Boolean? = null,
    /** Серий вышло на данный момент (для онгоингов); null = источник не сообщил. */
    val airedEpisodes: Int? = null,
    /** Заявленное общее число серий; null = не анонсировано / источник не сообщил. */
    val totalEpisodes: Int? = null,
    /** Статус сырым кодом источника: `RELEASING`, `FINISHED`, `ongoing`, `released`… */
    val statusRaw: String? = null,
    /** Формат выпуска сырым кодом источника: `TV`, `ONA`, `MOVIE`, `tv`, `ova`… */
    val format: String? = null,
    /** Первоисточник тайтла: `MANGA`, `LIGHT_NOVEL`, `ORIGINAL`… */
    val sourceMaterial: String? = null,
    /** Главная студия. */
    val studio: String? = null,
    /** Сезон выхода сырым кодом: `WINTER`, `SPRING`, `SUMMER`, `FALL`. */
    val season: String? = null,
    /**
     * Год выпуска. У аниме — год сезона; у MOVIE/SERIES — год релиза/премьеры,
     * используемый дедупом ремейков и адаптаций-омонимов.
     */
    val seasonYear: Int? = null,
    /** Оригинальное название источника для строгой title+year ступени дедупа MOVIE/SERIES. */
    val originalTitle: String? = null,
    /** Явное английское локализованное название, если источник/языковой запрос его дал. */
    val titleEn: String? = null,
    /** Явное русское локализованное название, если источник/языковой запрос его дал. */
    val titleRu: String? = null,
    /** TMDB/Kinopoisk id-пара для MOVIE/SERIES результатов. Пусто у аниме/манга-источников. */
    val externalIds: ExternalIds = ExternalIds(),
)
