package com.example.myapplication.domain.enrichment

import com.example.myapplication.data.models.Anime

/**
 * Пользователь пометил привязку тайтла к каталогу (AniList/MAL/Shikimori) как ошибочную.
 *
 * Отдельного поля в схеме нет, поэтому метка живёт в уже существующих отметках «не найдено»: их
 * проверяют по формуле «прошло ли больше TTL с этой даты», а дата из далёкого будущего никогда не
 * истекает. Автоматический поиск по названию (проверка серий, расклад сезонов, обогащение) тайтл
 * с такой меткой пропускает и не возвращает на место чужие id и обложку.
 */
const val SOURCE_REJECTED_AT: Long = 4_000_000_000_000L

fun Anime.isSourceRejected(): Boolean =
    anilistNotFoundAt == SOURCE_REJECTED_AT &&
        malNotFoundAt == SOURCE_REJECTED_AT &&
        shikimoriNotFoundAt == SOURCE_REJECTED_AT

/** Копия записи без привязки к каталогам и с меткой «источник неверен». Обложка снимается отдельно. */
fun Anime.withRejectedSource(): Anime = copy(
    anilistId = null,
    malId = null,
    shikimoriId = null,
    titleEn = null,
    anilistNotFoundAt = SOURCE_REJECTED_AT,
    malNotFoundAt = SOURCE_REJECTED_AT,
    shikimoriNotFoundAt = SOURCE_REJECTED_AT,
)
