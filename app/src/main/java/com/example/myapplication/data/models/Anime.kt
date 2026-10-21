package com.example.myapplication.data.models

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class Anime(
    val id: String,
    val title: String,
    /** Английское название (автообогащение). null = ещё не заполнено. */
    val titleEn: String? = null,
    /** Русское название (обратное обогащение через Shikimori). null = ещё не заполнено. */
    val titleRu: String? = null,
    val episodes: Int,
    /** 10-балльная шкала с одной цифрой после запятой (0 = не оценено). См. [RatingScale]. */
    val rating: Float,
    val imageFileName: String?,
    val orderIndex: Int,
    val dateAdded: Long,
    val isFavorite: Boolean = false,
    val tags: ImmutableList<String> = persistentListOf(),
    val categoryType: String = "",
    val comment: String = "",
    val anilistId: Int? = null,
    val malId: Int? = null,
    val shikimoriId: Int? = null,
    val anilistNotFoundAt: Long? = null,
    val malNotFoundAt: Long? = null,
    val shikimoriNotFoundAt: Long? = null,
    val mediaType: MediaType = MediaType.ANIME,
    val tmdbId: Int? = null,
    val kinopoiskId: Int? = null,
    val tmdbNotFoundAt: Long? = null,
    val kinopoiskNotFoundAt: Long? = null,
    /** Канонический IMDb id вида `tt0412142`. Строка: ведущие нули значащие. */
    val imdbId: String? = null,
    /** Статус просмотра, выбранный пользователем. null = «авто» по прогрессу, см. [effectiveWatchStatus]. */
    val watchStatus: WatchStatus? = null,
    /** Год релиза для фильтра. null = ещё не искали, 0 = источник года не дал. */
    val releaseYear: Int? = null,
)

enum class MediaType {
    ANIME,
    MANGA,
    MOVIE,
    SERIES;

    companion object {
        /**
         * `categoryType` (строка раздела поиска / источника) → тип записи.
         *
         * Источники своему типу не свидетели: Shikimori отдаёт `"ANIME"` и для манги, потому что
         * `/api/mangas` мапится тем же `toApiSearchResult()`. Единственный надёжный признак —
         * раздел, в котором тайтл нашли; он и проставляется в `categoryType` на выходе
         * `ApiService.searchApi`. Здесь только перевод строки в enum.
         *
         * `"TV_SERIES"` — legacy-алиас `SERIES`: до разделения фильмов и сериалов на отдельные
         * типы оба схлопывались в один `TV_SERIES`; старые сохранённые категории/пришедшие от не
         * обновлённых клиентов значения так и остаются валидными.
         *
         * null = строка про тип ничего не говорит (пустая, неизвестная) — решает вызывающий.
         */
        fun fromCategoryType(raw: String?): MediaType? = when (raw?.trim()?.uppercase()) {
            "MANGA" -> MANGA
            "ANIME" -> ANIME
            "MOVIE" -> MOVIE
            "SERIES", "TV_SERIES", "TV" -> SERIES
            else -> null
        }

        /**
         * `mediaType`, прочитанный как уже СОХРАНЁННОЕ значение (SQLDelight-строка, значение из
         * синка) — не строка раздела поиска. В отличие от [fromCategoryType], никогда не
         * возвращает null: у персистентной записи тип обязан быть каким-то, неизвестная/пустая
         * строка резолвится в [ANIME] (тот же дефолт, которым раньше был обвешан прямой
         * `MediaType.valueOf(...)`).
         *
         * Обязательна на границе десериализации (БД-маппер, sync) вместо `MediaType.valueOf`:
         * голый `valueOf` падает на легаси `"TV_SERIES"` после того, как эта константа исчезла
         * из enum.
         */
        fun fromPersistedValue(raw: String): MediaType {
            val normalized = raw.trim().uppercase()
            if (normalized == "TV_SERIES") return SERIES
            return runCatching { valueOf(normalized) }.getOrDefault(ANIME)
        }
    }
}
