package com.example.myapplication.domain.stats

import com.example.myapplication.data.models.Anime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Снапшот данных одной карточки статистики — ровно те цифры, что видит пользователь.
 * Служит входом для AI-объяснения и источником [fingerprint] для инвалидации кэша.
 */
data class StatsCardSnapshot(
    val kind: StatsCardKind,
    val barData: List<BarChartEntry> = emptyList(),
    val donutData: DonutChartData? = null,
    val totalAnime: Int = 0,
    val avgRating: Double = 0.0,
    val totalEpisodes: Int = 0,
    val favorites: Int = 0,
) {
    /**
     * Стабильный «отпечаток» именно тех цифр, что видит пользователь в этой карточке.
     * Фингерпринт на карточку, не на коллекцию: «избранное» на одном тайтле меняет
     * только OVERVIEW и не жжёт токены на пересчёт жанровых карточек.
     */
    fun fingerprint(): String {
        val data = when (kind) {
            StatsCardKind.RATING_BY_GENRE -> barData.joinToString("|") {
                "${it.tagId}:${fmt(it.averageRating)}:${it.titleCount}"
            }
            StatsCardKind.GENRE_FREQUENCY -> donutData?.let { d ->
                d.slices.joinToString("|") { "${it.tagId}:${it.count}" } + "/${d.totalTitles}"
            }.orEmpty()
            StatsCardKind.OVERVIEW -> "$totalAnime:${fmt(avgRating)}:$totalEpisodes:$favorites"
        }
        // Версия промпта в отпечатке: правка формулировки объяснения инвалидирует кэш.
        return "$PROMPT_VERSION|$data"
    }

    /** Есть ли в карточке данные, о которых вообще имеет смысл спрашивать AI. */
    fun hasSufficientData(): Boolean = when (kind) {
        StatsCardKind.RATING_BY_GENRE -> barData.isNotEmpty()
        StatsCardKind.GENRE_FREQUENCY -> donutData != null && donutData.slices.isNotEmpty()
        StatsCardKind.OVERVIEW -> totalAnime > 0
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

    companion object {
        /** Меняем при правке промпта объяснений — форсит перегенерацию кэша. */
        private const val PROMPT_VERSION = "v4"

        fun build(kind: StatsCardKind, animeList: List<Anime>): StatsCardSnapshot = when (kind) {
            StatsCardKind.RATING_BY_GENRE -> StatsCardSnapshot(
                kind = kind,
                barData = buildBarChartData(animeList),
            )
            StatsCardKind.GENRE_FREQUENCY -> StatsCardSnapshot(
                kind = kind,
                donutData = buildDonutChartData(animeList),
            )
            StatsCardKind.OVERVIEW -> {
                val avg = if (animeList.isNotEmpty()) animeList.map { it.rating }.average() else 0.0
                StatsCardSnapshot(
                    kind = kind,
                    totalAnime = animeList.size,
                    // Округляем до одного знака (как в UI), чтобы шум double не менял фингерпринт.
                    avgRating = (avg * 10).roundToInt() / 10.0,
                    totalEpisodes = animeList.sumOf { it.episodes },
                    favorites = animeList.count { it.isFavorite },
                )
            }
        }
    }
}
