package com.example.myapplication.domain.recommendations

import kotlinx.serialization.Serializable

/**
 * Итоговая карточка рекомендации. Сериализуется в файловый кэш as-is,
 * поэтому все поля — примитивы/списки.
 */
@Serializable
data class RecommendationItem(
    /** Стабильный ключ дедупа: "anilist:123" / "shikimori:45" / "mal:9" / "title:<norm>". */
    val key: String,
    val title: String,
    val altTitle: String? = null,
    val coverUrl: String,
    /** Сырые жанры источника (EN у AniList, RU у Shikimori). */
    val genres: List<String> = emptyList(),
    /** Рейтинг источника, нормализован к 0..100. */
    val externalRating: Int? = null,
    val episodes: Int = 0,
    val description: String = "",
    val source: String,
    val externalId: String? = null,
    val categoryType: String = "ANIME",
    val score: Float = 0f,
    /** Сид, из related-графа которого пришёл кандидат — для подписи "Похоже на X". */
    val seedTitle: String? = null,
    /** Тип контента: в кэше лежат пулы всех типов, квота собирает из них колоду сессии. */
    val contentType: ContentType = ContentType.ANIME,
    /** TMDB id для фильмов/сериалов — нужен, чтобы «В коллекцию» привязал тайтл к TMDB. */
    val tmdbId: Int? = null,
    /** Подтверждения источников — основа честных объяснений «Похоже на X»; максимум [MAX_EVIDENCE]. */
    val evidence: List<RecommendationEvidence> = emptyList(),
) {
    companion object {
        const val MAX_EVIDENCE = 8
    }
}

/** Снапшот пересчёта: полезная нагрузка кэша с TTL и сигнатурой библиотеки. */
@Serializable
data class RecommendationsSnapshot(
    val items: List<RecommendationItem>,
    val computedAtMillis: Long,
    /** Хэш состояния библиотеки (ids+рейтинги) на момент пересчёта — для инвалидации. */
    val librarySignature: String,
    /** 0..1 — уверенность движка (растёт с числом оценённых тайтлов). */
    val confidence: Float,
    /** true → cold-start (тренды), подпись в UI другая. */
    val isColdStart: Boolean = false,
    /** Язык, под который собран пул (RU — Shikimori-first): смена языка инвалидирует кэш. */
    val language: String = "EN",
    /** Ревизия оценок свайпов на момент пересчёта: новая оценка делает кэш устаревшим. */
    val feedbackRevision: Int = 0,
    /** Состав коллекции на момент пересчёта: по нему VM считает квоты аниме/фильмы/сериалы. */
    val mix: ContentMix = ContentMix(),
    /** 1 — прежний скор (шкала до ~2), 2 — нормализованный [0,1]; от версии зависит амплитуда джиттера. */
    val scorerVersion: Int = 1,
    /** Подпись флагов источников/скорера на момент пересчёта; смена любого флага инвалидирует кэш. */
    val sourceFlags: String = "",
)

/** Cold-start лесенка (§2.5 плана). */
sealed class RecommendationStrategy {
    /** Библиотека пуста → глобальный тренд. */
    data object Onboarding : RecommendationStrategy()

    /** Тайтлы есть, оценок нет → related по недавним, сортировка по рейтингу источника. */
    data object PopularitySort : RecommendationStrategy()

    /** Есть оценки → взвешенный жанровый вектор. */
    data class WeightedVector(val confidence: Float) : RecommendationStrategy()
}
