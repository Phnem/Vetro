package com.example.myapplication.domain.recommendations

import com.example.myapplication.domain.normalizeForSearch
import kotlinx.serialization.Serializable
import kotlin.math.pow

/** Оценка пользователя рекомендации: свайп вверх — понравилось, вниз — не понравилось. */
@Serializable
enum class FeedbackVerdict { APPROVED, DISAPPROVED }

/**
 * Одна оценка карточки. Хранит всё, что нужно для уточнения профиля без повторного обращения
 * к источникам: жанры (сырые, маппятся в теги при пересчёте) и сид, из графа которого карточка пришла.
 */
@Serializable
data class FeedbackEntry(
    /** Ключ карточки на момент оценки (может меняться между пересчётами — не единственный идентификатор). */
    val key: String,
    /** Нормализованные название и альт-название: по ним оценка находит карточку после пересчёта. */
    val titleKeys: List<String>,
    val verdict: FeedbackVerdict,
    val genres: List<String> = emptyList(),
    val seedTitle: String? = null,
    val atMillis: Long,
    /** Тип карточки: аниме и экран имеют разные профили вкуса, оценки не должны утекать между ними. */
    val contentType: ContentType = ContentType.ANIME,
)

/** Содержимое файла оценок. [revision] растёт при любом изменении — им инвалидируется кэш рекомендаций. */
@Serializable
data class FeedbackState(
    val revision: Int = 0,
    val entries: List<FeedbackEntry> = emptyList(),
)

/** Быстрая проверка «карточка уже оценена» по ключу и по нормализованным названиям. */
class FeedbackIndex(entries: List<FeedbackEntry>) {
    private val keys: Set<String> = buildSet {
        for (entry in entries) {
            add(entry.key)
            addAll(entry.titleKeys)
        }
    }

    fun isJudged(vararg candidateKeys: String?): Boolean =
        candidateKeys.any { !it.isNullOrEmpty() && it in keys }

    fun isJudged(item: RecommendationItem): Boolean = isJudged(
        item.key,
        item.title.normalizeForSearch(),
        item.altTitle?.normalizeForSearch(),
    )

    companion object {
        val Empty = FeedbackIndex(emptyList())
    }
}

fun RecommendationItem.toFeedbackEntry(verdict: FeedbackVerdict, nowMillis: Long): FeedbackEntry = FeedbackEntry(
    key = key,
    titleKeys = listOfNotNull(
        title.normalizeForSearch().takeIf { it.isNotEmpty() },
        altTitle?.normalizeForSearch()?.takeIf { it.isNotEmpty() },
    ),
    verdict = verdict,
    genres = genres,
    seedTitle = seedTitle?.takeIf { it.isNotBlank() },
    atMillis = nowMillis,
    contentType = contentType,
)

/**
 * Превращает оценки свайпов в сигналы ранжирования. Чистый Kotlin, без Android.
 *
 * Свайп — слабее явной оценки тайтла в коллекции (там пользователь посмотрел), но накапливается:
 * жанры одобренных карточек усиливаются, отклонённых — гасятся, а сидам, чьи рекомендации
 * чаще одобряют, достаётся небольшая прибавка к score.
 */
object FeedbackSignals {

    /** Вклад одной оценки в вектор вкуса — в единицах 10-балльной шкалы (оценка тайтла 6±4). */
    const val GENRE_WEIGHT = 1.5f

    /** Полураспад оценки: вкус дрейфует, старые свайпы весят вдвое меньше через ~12 месяцев. */
    const val HALF_LIFE_MILLIS = 365L * 24 * 60 * 60 * 1000

    private const val SMOOTHING = 2f

    /** Сырые веса «тег → вклад свайпов», добавляются к вкусу коллекции до нормализации. */
    fun genreWeights(
        entries: List<FeedbackEntry>,
        genreToTagId: (String) -> String?,
        nowMillis: Long,
    ): Map<String, Float> {
        if (entries.isEmpty()) return emptyMap()
        val weights = HashMap<String, Float>()
        for (entry in entries) {
            val sign = if (entry.verdict == FeedbackVerdict.APPROVED) 1f else -1f
            val age = (nowMillis - entry.atMillis).coerceAtLeast(0L).toDouble()
            val decay = 0.5.pow(age / HALF_LIFE_MILLIS).toFloat()
            val tags = entry.genres.mapNotNull(genreToTagId).distinct()
            for (tag in tags) {
                weights[tag] = (weights[tag] ?: 0f) + sign * GENRE_WEIGHT * decay
            }
        }
        return weights
    }

    /**
     * Доверие к сиду −1..1: доля одобренных среди его карточек со сглаживанием
     * (один свайп не делает сид ни «золотым», ни «токсичным»).
     */
    fun seedTrust(entries: List<FeedbackEntry>): Map<String, Float> {
        val approved = HashMap<String, Int>()
        val rejected = HashMap<String, Int>()
        for (entry in entries) {
            val seed = entry.seedTitle ?: continue
            val bucket = if (entry.verdict == FeedbackVerdict.APPROVED) approved else rejected
            bucket[seed] = (bucket[seed] ?: 0) + 1
        }
        return (approved.keys + rejected.keys).associateWith { seed ->
            val a = approved[seed] ?: 0
            val r = rejected[seed] ?: 0
            (a - r).toFloat() / (a + r + SMOOTHING)
        }
    }
}
