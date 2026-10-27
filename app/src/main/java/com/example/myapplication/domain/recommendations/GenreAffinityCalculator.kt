package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.WatchStatus
import kotlin.math.abs
import kotlin.math.pow

/**
 * Вектор «жанр → сила предпочтения» по коллекции пользователя.
 *
 * Оценки выше нейтральной (6 из 10) тянут жанры тайтла вверх, ниже — гасят.
 * Избранное добавляет фиксированный бонус. Результат нормализован в [-1, 1].
 * Ключи — теги приложения (id из GenreRepository), чистый Kotlin без Android.
 *
 * С [nowMillis] профиль «помнит время»: свежие тайтлы весят полностью, старые — с затуханием до
 * [RECENCY_FLOOR] (долгосрочный вкус не стирается, но краткосрочный перевешивает); брошенное без оценки
 * даёт умеренный минус (не запрет жанра), пересмотр — небольшой плюс. Без [nowMillis] поведение прежнее.
 */
class GenreAffinityCalculator {

    /**
     * [extraWeights] — сырой вклад вне коллекции (оценки свайпов рекомендаций, см. [FeedbackSignals]);
     * складывается с вкладом оценок до нормализации, поэтому профиль уточняется и без оценок тайтлов.
     */
    fun calculate(
        library: List<Anime>,
        extraWeights: Map<String, Float> = emptyMap(),
        nowMillis: Long? = null,
    ): Map<String, Float> {
        val raw = HashMap<String, Float>(extraWeights)
        for (anime in library) {
            if (anime.tags.isEmpty()) continue
            var weight = 0f
            if (anime.rating > 0) weight += anime.rating - NEUTRAL_RATING
            if (anime.isFavorite) weight += FAVORITE_BONUS
            if (nowMillis != null) {
                // Оценка уже несёт антипатию; «брошено» без оценки — единственный сигнал о неприязни.
                if (anime.watchStatus == WatchStatus.DROPPED && anime.rating <= 0f) weight -= DROPPED_PENALTY
                if (anime.watchStatus == WatchStatus.REWATCHING) weight += REWATCH_BONUS
                weight *= recency(anime.dateAdded, nowMillis)
            }
            if (weight == 0f) continue
            for (tag in anime.tags) {
                raw[tag] = (raw[tag] ?: 0f) + weight
            }
        }
        if (raw.isEmpty()) return emptyMap()
        val maxAbs = raw.values.maxOf { abs(it) }.takeIf { it > 0f } ?: return emptyMap()
        return raw.mapValues { (_, v) -> (v / maxAbs).coerceIn(-1f, 1f) }
    }

    private fun recency(addedMillis: Long, nowMillis: Long): Float {
        val age = (nowMillis - addedMillis).coerceAtLeast(0L).toDouble()
        return RECENCY_FLOOR + (1f - RECENCY_FLOOR) * 0.5.pow(age / HALF_LIFE_MILLIS).toFloat()
    }

    companion object {
        /** Нейтральная точка 10-балльной шкалы: ниже — антипатия, выше — симпатия. */
        const val NEUTRAL_RATING = 6f
        /** Бонус избранного в единицах 10-балльной шкалы (прежний 1★ ≈ 2 балла). */
        const val FAVORITE_BONUS = 2f
        /** Штраф брошенного без оценки — меньше типичной низкой оценки: вкус не запрещаем целиком. */
        const val DROPPED_PENALTY = 2f
        const val REWATCH_BONUS = 1f
        /** Полураспад ~12 месяцев — стартовая настройка, подбирается на replay-тестах. */
        const val HALF_LIFE_MILLIS = 365L * 24 * 60 * 60 * 1000
        /** Доля веса, которую старый тайтл не теряет: долгосрочный профиль остаётся. */
        const val RECENCY_FLOOR = 0.4f
    }
}
