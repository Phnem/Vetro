package com.example.myapplication.network.ids

/**
 * VETRO-TAIL[title-id-source]
 *
 * Хвост для новых источников информационной части (id, названия, описание, постер). Новый
 * источник — это один класс, реализующий этот интерфейс, и одна строка в `enrichmentModule`
 * (`single<TitleIdContributor> { … }`). Резолвер сам вызывает все зарегистрированные источники
 * после встроенных шагов, оборачивает их в защиту от сбоев и пишет имя в `ResolvedTitle.sources`.
 *
 * Правила:
 *  - вклад только дополняет: уже найденные значения не перезаписываются;
 *  - нет данных или выключен ключ — вернуть null, не бросать;
 *  - [contribute] получает всё, что собрано к этому моменту, чтобы искать по самым надёжным id.
 *
 * Список всех хвостов проекта: `docs/agents/tails.md`.
 */
interface TitleIdContributor {
    /** Короткое имя для журнала («IMDb-index», «Trakt»). */
    val name: String

    suspend fun contribute(query: ResolveQuery, soFar: ResolvedTitle): TitleContribution?
}

/** То, что источник добавляет к записи. Пустые поля игнорируются. */
data class TitleContribution(
    val ids: CrossIds = CrossIds(),
    val titleRu: String? = null,
    val titleEn: String? = null,
    val altTitles: List<String> = emptyList(),
    val descriptionRu: String? = null,
    val descriptionEn: String? = null,
    val posterUrl: String? = null,
)

internal fun ResolvedTitle.with(add: TitleContribution, source: String): ResolvedTitle {
    val newRu = titleRu ?: add.titleRu?.takeIf { it.isNotBlank() }
    val newEn = titleEn ?: add.titleEn?.takeIf { it.isNotBlank() }
    return copy(
        ids = ids.merge(add.ids),
        titleRu = newRu,
        titleEn = newEn,
        altTitles = (altTitles + add.altTitles).distinct().filter { it.isNotBlank() && it != newRu && it != newEn },
        descriptionRu = descriptionRu ?: add.descriptionRu?.takeIf { it.isNotBlank() },
        descriptionEn = descriptionEn ?: add.descriptionEn?.takeIf { it.isNotBlank() },
        posterUrl = posterUrl ?: add.posterUrl?.takeIf { it.isNotBlank() },
        sources = sources + source,
    )
}
