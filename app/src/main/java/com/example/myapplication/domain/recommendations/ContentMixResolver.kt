package com.example.myapplication.domain.recommendations

import kotlin.math.roundToInt

/**
 * Пропорция выдачи по составу коллекции (§2 плана). Чистая логика без сети и Android.
 *
 * Доля библиотеки управляет СОСТАВОМ колоды, а не оценкой качества: сырой скор аниме и кино
 * напрямую не сравнивается — сначала ранжирование внутри типа, затем квоты.
 */
object ContentMixResolver {
    const val TOTAL = 20

    /** Ниже этого числа записей вывод «100% одного типа» недостоверен. */
    const val MIN_STATS = 5

    /** Доминирующая группа получает всю колоду (20/0). */
    const val DOMINANCE_ENTER = 0.85f

    /** Гистерезис: уже доминирующая группа остаётся ею, пока доля не упала ниже этого порога. */
    const val DOMINANCE_EXIT = 0.80f

    /** Заметная доля обеих групп гарантирует минимум слотов каждой. */
    const val VISIBLE_SHARE = 0.15f
    const val MIN_VISIBLE_SLOTS = 2

    /**
     * @param available сколько кандидатов реально есть по типам (null — не ограничивать): недостающие
     *   слоты отдаются типам с запасом.
     * @param previous квота прошлого расчёта — вход для гистерезиса около порога 85%.
     */
    fun resolve(
        mix: ContentMix,
        available: Map<ContentType, Int>? = null,
        previous: Quota? = null,
    ): Quota {
        val raw = rawQuota(mix, previous)
        return if (available == null) raw else redistribute(raw, available)
    }

    private fun rawQuota(mix: ContentMix, previous: Quota?): Quota {
        val total = mix.total
        val animeSlots = when {
            // Холодный старт: ничего не известно — поровну, без ложного профиля.
            total == 0 -> TOTAL / 2
            // Мало данных: смещаем к имеющемуся, но не делаем вывод «только аниме» из одной записи.
            total < MIN_STATS -> (TOTAL * (0.5f * 0.5f + 0.5f * mix.anime / total)).roundToInt()
            else -> {
                val shareAnime = mix.anime.toFloat() / total
                val shareScreen = 1f - shareAnime
                val animeThreshold = if (previous != null && previous.anime == TOTAL) DOMINANCE_EXIT else DOMINANCE_ENTER
                val screenThreshold = if (previous != null && previous.anime == 0) DOMINANCE_EXIT else DOMINANCE_ENTER
                when {
                    shareAnime >= animeThreshold -> TOTAL
                    shareScreen >= screenThreshold -> 0
                    else -> {
                        val slots = (TOTAL * shareAnime).roundToInt()
                        if (shareAnime >= VISIBLE_SHARE && shareScreen >= VISIBLE_SHARE) {
                            slots.coerceIn(MIN_VISIBLE_SLOTS, TOTAL - MIN_VISIBLE_SLOTS)
                        } else slots
                    }
                }
            }
        }
        val screenSlots = TOTAL - animeSlots
        val movieSlots = splitScreen(screenSlots, mix)
        return Quota(animeSlots, movieSlots, screenSlots - movieSlots)
    }

    /** Фильмы и сериалы делят экранную квоту по реальной структуре коллекции. */
    private fun splitScreen(screenSlots: Int, mix: ContentMix): Int {
        if (screenSlots == 0) return 0
        if (mix.screen == 0) return (screenSlots + 1) / 2
        val movies = (screenSlots.toFloat() * mix.movies / mix.screen).roundToInt()
        return if (mix.movies > 0 && mix.series > 0 && screenSlots >= 4) movies.coerceIn(1, screenSlots - 1) else movies
    }

    /** Слоты типа, которому не хватило кандидатов, достаются типам с запасом — по убыванию их исходной квоты. */
    private fun redistribute(raw: Quota, available: Map<ContentType, Int>): Quota {
        val order = ContentType.entries.sortedByDescending { raw.of(it) }
        val taken = ContentType.entries.associateWith { minOf(raw.of(it), available[it] ?: 0) }.toMutableMap()
        var surplus = raw.total - taken.values.sum()
        while (surplus > 0) {
            val receiver = order.firstOrNull { (available[it] ?: 0) > taken.getValue(it) } ?: break
            taken[receiver] = taken.getValue(receiver) + 1
            surplus--
        }
        return Quota(taken.getValue(ContentType.ANIME), taken.getValue(ContentType.MOVIE), taken.getValue(ContentType.TV))
    }
}

/** Сборка колоды из пулов по типам: отбор по квоте и равномерное чередование типов. */
object QuotaAllocator {

    /**
     * Берёт из каждого пула первые `quota.of(type)` (внутри типа сохраняется персональный ранг) и
     * чередует типы по детерминированному графику наибольшего отставания, чтобы не было двух больших блоков.
     */
    fun <T> interleave(pools: Map<ContentType, List<T>>, quota: Quota): List<T> {
        val picked = ContentType.entries.associateWith { pools[it].orEmpty().take(quota.of(it)) }
        val n = picked.values.sumOf { it.size }
        if (n == 0) return emptyList()
        val taken = ContentType.entries.associateWith { 0 }.toMutableMap()
        val out = ArrayList<T>(n)
        for (step in 1..n) {
            val next = ContentType.entries
                .filter { taken.getValue(it) < picked.getValue(it).size }
                .maxBy { picked.getValue(it).size.toDouble() * step / n - taken.getValue(it) }
            out += picked.getValue(next)[taken.getValue(next)]
            taken[next] = taken.getValue(next) + 1
        }
        return out
    }
}
