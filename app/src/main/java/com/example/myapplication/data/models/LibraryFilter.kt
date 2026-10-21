package com.example.myapplication.data.models

import java.util.Calendar

/**
 * Фильтры библиотеки из шторки «Сортировка и фильтры» (жанры живут отдельно — `filterTags`).
 *
 * Пустой набор / null-диапазон = «Все», ограничения нет. Диапазон, совпадающий с полными границами
 * слайдера, нормализуется в null ([normalized]), поэтому «не трогал» и «растянул до краёв» — одно
 * и то же.
 *
 * Край диапазона на границе слайдера — «без предела» с этой стороны: нижний край года 1950 не
 * отсекает старые фильмы, верхний край серий «2000» значит «2000 и больше».
 */
data class LibraryFilter(
    val statuses: Set<WatchStatus> = emptySet(),
    val yearRange: IntRange? = null,
    val ratingRange: ClosedFloatingPointRange<Float>? = null,
    val episodesRange: IntRange? = null,
) {
    val isActive: Boolean
        get() = statuses.isNotEmpty() || yearRange != null || ratingRange != null || episodesRange != null

    /** Диапазоны, равные полным границам слайдеров, превращаются в «без ограничения». */
    fun normalized(): LibraryFilter = copy(
        yearRange = yearRange?.takeUnless { it.first <= YEAR_MIN && it.last >= currentYear() },
        ratingRange = ratingRange?.takeUnless { it.start <= RATING_MIN && it.endInclusive >= RATING_MAX },
        episodesRange = episodesRange?.takeUnless { it.first <= EPISODES_MIN && it.last >= EPISODES_MAX },
    )

    /** Статус проходит фильтр. Отдельно от [matchesRanges]: статус «по умолчанию» зависит от прогресса просмотра. */
    fun matchesStatus(anime: Anime, auto: WatchStatus?): Boolean =
        statuses.isEmpty() || anime.effectiveWatchStatus(auto) in statuses

    /** Год, рейтинг и число серий — всё, что решается по самой записи. */
    fun matchesRanges(anime: Anime): Boolean {
        yearRange?.let { range ->
            // Год неизвестен (null или 0 = «искали, не нашли») — под суженный диапазон не подходит.
            val year = anime.releaseYear?.takeIf { it > 0 } ?: return false
            if (range.first > YEAR_MIN && year < range.first) return false
            if (range.last < currentYear() && year > range.last) return false
        }

        ratingRange?.let { range ->
            // Сравнение по хранимой десятой: Float 7.3 и 7.3f из слайдера могут расходиться в хвосте.
            val tenths = RatingScale.displayToStored(anime.rating)
            if (tenths < RatingScale.displayToStored(range.start)) return false
            if (tenths > RatingScale.displayToStored(range.endInclusive)) return false
        }

        episodesRange?.let { range ->
            if (range.first > EPISODES_MIN && anime.episodes < range.first) return false
            if (range.last < EPISODES_MAX && anime.episodes > range.last) return false
        }
        return true
    }

    companion object {
        const val MAX_STATUSES = 3
        const val YEAR_MIN = 1950
        const val RATING_MIN = 0f
        const val RATING_MAX = 10f
        const val EPISODES_MIN = 1
        const val EPISODES_MAX = 2000

        fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)
    }
}
