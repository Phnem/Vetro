package com.example.myapplication.domain.enrichment

import com.example.myapplication.data.models.LibraryFilter
import com.example.myapplication.network.AnimeDetails

/**
 * Год релиза из карточки источника: год сезона, а если его нет — год даты выхода. Отсекает
 * заведомо неверные значения (0, «9999» из заглушек), чтобы они не попадали в фильтр года.
 */
fun AnimeDetails.releaseYearOrNull(): Int? =
    (seasonYear ?: airedOn?.take(4)?.toIntOrNull())
        ?.takeIf { it in 1870..(LibraryFilter.currentYear() + 2) }
