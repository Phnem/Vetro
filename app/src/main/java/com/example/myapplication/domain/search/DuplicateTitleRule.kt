package com.example.myapplication.domain.search

import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.normalizeForSearch

/**
 * Правило «это одна и та же запись» и схлопывание списка коллекции.
 *
 * Чистые функции без зависимостей от Android и Compose — схлопывание **удаляет пользовательские
 * записи**, и такая логика должна быть покрыта обычными тестами, а не проверяться на живой БД.
 *
 * Правило (см. `spec.md`, раздел Domain rules): записи считаются одной, если совпадает
 * [Anime.mediaType] И (совпадает любой внешний id, ИЛИ совпадает нормализованное название).
 * Разный `mediaType` дубликатом не является никогда — аниме и манга с одним названием и даже
 * одним id источника это две разные записи.
 */

/** Названия записи (основное и оба перевода) в нормализованном виде. */
private fun Anime.titleKeys(): Set<String> =
    listOfNotNull(title, titleEn, titleRu)
        .map { it.normalizeForSearch() }
        .filterTo(mutableSetOf()) { it.isNotEmpty() }

/** Внешние id записи как пары «источник → значение», чтобы не сравнивать anilistId с malId. */
private fun Anime.externalIds(): List<Pair<String, Int>> = buildList {
    anilistId?.let { add("anilist" to it) }
    malId?.let { add("mal" to it) }
    shikimoriId?.let { add("shikimori" to it) }
    tmdbId?.let { add("tmdb" to it) }
    kinopoiskId?.let { add("kinopoisk" to it) }
}

/**
 * Одна ли это запись.
 *
 * Названия сравниваются на **строгое равенство** нормализованных строк, а не по вхождению.
 * Подстрочное сравнение (как в `isAddedInMemory`, где оно допустимо — там оно лишь красит кнопку)
 * здесь недопустимо: оно считает «Naruto» и «Naruto Shippuden» одной записью, и схлопывание
 * удалило бы отдельный тайтл.
 */
fun isDuplicate(a: Anime, b: Anime): Boolean {
    if (a.mediaType != b.mediaType) return false

    if (a.mediaType == com.example.myapplication.data.models.MediaType.MOVIE ||
        a.mediaType == com.example.myapplication.data.models.MediaType.SERIES
    ) {
        if (a.tmdbId != null && b.tmdbId != null) return a.tmdbId == b.tmdbId
        if (a.kinopoiskId != null && a.kinopoiskId == b.kinopoiskId) return true
    }

    val aIds = a.externalIds()
    if (aIds.isNotEmpty()) {
        val bIds = b.externalIds()
        if (aIds.any { it in bIds }) return true
    }

    return a.titleKeys().any { it in b.titleKeys() }
}

/**
 * Насколько запись заполнена. Больше — тем важнее её сохранить.
 *
 * Веса, а не простой подсчёт полей: потерять избранное или оценку несопоставимо хуже, чем потерять
 * скачанный постер. Избранное перевешивает всё остальное вместе взятое.
 */
private fun richness(anime: Anime): Int {
    var score = 0
    if (anime.isFavorite) score += 1000
    if (anime.rating > 0f) score += 100
    if (anime.comment.isNotBlank()) score += 50
    score += anime.tags.size * 10
    score += anime.externalIds().size * 5
    if (!anime.titleEn.isNullOrBlank()) score += 2
    if (!anime.titleRu.isNullOrBlank()) score += 2
    if (anime.imageFileName != null) score += 2
    if (anime.episodes > 0) score += 1
    return score
}

/**
 * Победитель пары: более заполненная запись, при равенстве — более ранняя (оригинал, а не копия,
 * возникшая от повторного нажатия).
 */
private fun richer(a: Anime, b: Anime): Anime = when {
    richness(a) != richness(b) -> if (richness(a) > richness(b)) a else b
    else -> if (a.dateAdded <= b.dateAdded) a else b
}

/**
 * Переносит в выжившую запись то, чего у неё нет, из отброшенной.
 *
 * Только дозаполнение: ни одно непустое поле выжившей не перезаписывается. Без этого схлопывание
 * теряло бы внешние id, по которым запись потом обогащается, — и «почистили дубликаты» обернулось
 * бы тем, что тайтл перестал подтягивать обновления.
 */
private fun absorb(survivor: Anime, discarded: Anime): Anime = survivor.copy(
    titleEn = survivor.titleEn?.takeIf { it.isNotBlank() } ?: discarded.titleEn,
    titleRu = survivor.titleRu?.takeIf { it.isNotBlank() } ?: discarded.titleRu,
    imageFileName = survivor.imageFileName ?: discarded.imageFileName,
    anilistId = survivor.anilistId ?: discarded.anilistId,
    malId = survivor.malId ?: discarded.malId,
    shikimoriId = survivor.shikimoriId ?: discarded.shikimoriId,
    tmdbId = survivor.tmdbId ?: discarded.tmdbId,
    kinopoiskId = survivor.kinopoiskId ?: discarded.kinopoiskId,
    watchStatus = survivor.watchStatus ?: discarded.watchStatus,
    releaseYear = survivor.releaseYear?.takeIf { it > 0 } ?: discarded.releaseYear ?: survivor.releaseYear,
    episodes = if (survivor.episodes > 0) survivor.episodes else discarded.episodes,
)

/**
 * Схлопывает дубликаты, сохраняя порядок первого появления.
 *
 * Из каждой группы остаётся одна запись — более заполненная, дозаполненная тем, что было только у
 * отброшенных.
 */
fun collapseDuplicates(list: List<Anime>): List<Anime> {
    if (list.size < 2) return list
    val survivors = mutableListOf<Anime>()
    for (candidate in list) {
        val index = survivors.indexOfFirst { isDuplicate(it, candidate) }
        if (index < 0) {
            survivors += candidate
        } else {
            val current = survivors[index]
            val winner = richer(current, candidate)
            val loser = if (winner === current) candidate else current
            survivors[index] = absorb(winner, loser)
        }
    }
    return survivors
}
