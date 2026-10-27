package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import kotlinx.serialization.Serializable

/**
 * Тип рекомендуемого контента. Фильм и сериал — разные сущности: `MOVIE:123` и `TV:123` в TMDB
 * не связаны, поэтому тип всегда входит в ключ кандидата и дедуп никогда не склеивает через границу типов.
 */
@Serializable
enum class ContentType {
    ANIME, MOVIE, TV;

    /** Фильмы и сериалы делят один «экранный» профиль вкуса, аниме — отдельный. */
    val isScreen: Boolean get() = this != ANIME

    companion object {
        /** MANGA и прочее в рекомендации аниме/кино не входит. */
        fun of(mediaType: MediaType): ContentType? = when (mediaType) {
            MediaType.ANIME -> ANIME
            MediaType.MOVIE -> MOVIE
            MediaType.SERIES -> TV
            MediaType.MANGA -> null
        }
    }
}

/** Откуда и как кандидат попал в пул: от этого зависит вес связи при ранжировании. */
@Serializable
enum class SignalKind { RECOMMENDATION, SIMILAR, MULTISEED, TRENDING, DISCOVER }

/**
 * Одно подтверждение кандидата от источника. Объединение дублей хранит ВСЕ подтверждения, а не
 * только самую полную карточку: два API про один тайтл от одного сида — одно независимое
 * подтверждение, а те же API от разных сидов — несколько.
 */
@Serializable
data class RecommendationEvidence(
    val source: String,
    val seedKey: String? = null,
    val seedTitle: String? = null,
    /** Сырая сила связи у источника (AniList: голоса за связь); null — источник её не даёт. */
    val rawLinkWeight: Double? = null,
    val rankInSource: Int? = null,
    val kind: SignalKind = SignalKind.RECOMMENDATION,
)

/** Состав коллекции по типам — основа автоматической пропорции выдачи. Манга и аудиокниги не считаются. */
@Serializable
data class ContentMix(
    val anime: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
) {
    val screen: Int get() = movies + series
    val total: Int get() = anime + screen

    companion object {
        /** Считает ВСЮ коллекцию, включая записи без внешних id и оценок: id влияет на способ сбора, не на долю интересов. */
        fun of(library: List<Anime>): ContentMix {
            val unique = library.distinctBy { it.id }
            return ContentMix(
                anime = unique.count { it.mediaType == MediaType.ANIME },
                movies = unique.count { it.mediaType == MediaType.MOVIE },
                series = unique.count { it.mediaType == MediaType.SERIES },
            )
        }
    }
}

/** Сколько карточек каждого типа должно попасть в сессионную колоду. */
data class Quota(val anime: Int, val movies: Int, val series: Int) {
    val total: Int get() = anime + movies + series

    fun of(type: ContentType): Int = when (type) {
        ContentType.ANIME -> anime
        ContentType.MOVIE -> movies
        ContentType.TV -> series
    }
}

/** Сид из коллекции пользователя для запроса related-графов. */
data class RecSeed(
    val type: ContentType,
    /** Типизированный ключ `ANIME:anilist:123` / `MOVIE:tmdb:550`: сиды не путаются между типами. */
    val key: String,
    val title: String,
    /** Оценка пользователя, 10-балльная; 0 — не оценено. */
    val rating: Float,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val shikimoriId: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tags: Set<String> = emptySet(),
)

/**
 * Флаги этапов раскатки. По умолчанию включено то, что работает на уже имеющихся ключах (TMDB) и
 * проверено тестами; платные/нелицензированные источники выключены до проверки условий.
 */
data class RecommendationFlags(
    val tmdbRec: Boolean = true,
    val traktRelated: Boolean = false,
    val tasteDive: Boolean = false,
    /** Зарезервировано: AniDB не реализован (HTTP без TLS, нет надёжного ID-маппинга) — см. docs/recommendations. */
    val aniDb: Boolean = false,
    /** Нормализованный скор [0,1] с MMR; выключено = прежняя формула. */
    val newScorer: Boolean = true,
    /** Автоматическая пропорция аниме / фильмы+сериалы; выключено = только аниме, как раньше. */
    val adaptiveMix: Boolean = true,
)
