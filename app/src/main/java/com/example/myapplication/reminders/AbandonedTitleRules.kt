package com.example.myapplication.reminders

import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.regularSeasons

/** Чем считается прогресс тайтла: серии (аниме, сериал) или главы (манга). */
enum class ReminderUnit { EPISODES, CHAPTERS }

/** Тайтл, о котором можно напомнить: прошёл отбор по правилам, но ещё не по истории напоминаний. */
data class AbandonedCandidate(
    val animeId: String,
    val title: String,
    val unit: ReminderUnit,
    /** Сколько серий досмотрено или глав прочитано. */
    val doneCount: Int,
    /** Подпись места остановки: «S1 E5», «E5» или null (у манги её нет, есть число глав). */
    val position: String?,
    val lastActivityAt: Long,
    val idleDays: Int,
)

/** Что уже напоминали об одном тайтле. */
data class ReminderRecord(val lastAt: Long, val count: Int)

/**
 * Правила напоминаний о заброшенных тайтлах: чистые функции без Android, чтобы их можно было
 * проверить тестами.
 *
 * Тайтл заброшен, если: просмотрено (прочитано) минимум [MIN_UNITS], с последней активности прошло
 * минимум [MIN_IDLE_DAYS], пользователь не отметил его «Просмотрено» или «Брошено», и впереди ещё
 * есть вышедший, но не просмотренный материал. Последнее и есть проверка «не закончился ли
 * тайтл»: если смотреть больше нечего, напоминать не о чем.
 */
object AbandonedTitleRules {
    const val MIN_UNITS = 2
    const val MIN_IDLE_DAYS = 21

    /** Не чаще одного напоминания на всё приложение за это время. */
    const val GLOBAL_COOLDOWN_DAYS = 3

    /** О том же тайтле повторно не раньше, чем через столько дней. */
    const val TITLE_COOLDOWN_DAYS = 45

    /** Сколько раз можно напомнить об одном и том же простое: дальше человек, видимо, не хочет. */
    const val MAX_PER_TITLE = 2

    /** Окно показа по местному времени: ночью и рано утром не будим. */
    const val WINDOW_START_HOUR = 10
    const val WINDOW_END_HOUR = 21

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun idleDays(lastActivityAt: Long, now: Long): Int =
        ((now - lastActivityAt).coerceAtLeast(0L) / DAY_MS).toInt()

    fun isIdleLongEnough(lastActivityAt: Long, now: Long): Boolean =
        lastActivityAt > 0L && idleDays(lastActivityAt, now) >= MIN_IDLE_DAYS

    /** Явно завершённое или брошенное пользователем не трогаем. «Авто» (null) подходит. */
    fun isStatusEligible(status: WatchStatus?): Boolean =
        status != WatchStatus.COMPLETED && status != WatchStatus.DROPPED

    fun isInWindow(hourOfDay: Int): Boolean = hourOfDay in WINDOW_START_HOUR until WINDOW_END_HOUR

    /**
     * Сколько серий уже вышло и доступно, по тому же приоритету, что у карточки: расклад сезонов,
     * затем снимок выходящего сезона, затем сохранённый счётчик. null = источники молчат, и
     * проверить, не закончился ли тайтл, нечем (тогда не напоминаем).
     */
    fun releasedEpisodes(layout: SeasonEpisodesEntry?, airing: AiringProgress?, stored: Int): Int? {
        val seasons = layout.regularSeasons()
        if (seasons.isNotEmpty()) return seasons.sumOf { it.episodes }
        airing?.airedEpisodes?.takeIf { it > 0 }?.let { return it }
        return stored.takeIf { it > 0 }
    }

    /** Есть ли что продолжать: вышедшего больше, чем сделанного. Неизвестное число = нет. */
    fun hasUnfinishedContent(released: Int?, done: Int): Boolean = released != null && released > done

    /** Можно ли напомнить об этом тайтле с учётом истории. */
    fun passesHistory(record: ReminderRecord?, lastActivityAt: Long, now: Long): Boolean {
        if (record == null) return true
        // Человек возвращался к тайтлу после прошлого напоминания: это уже другой простой.
        if (lastActivityAt > record.lastAt) return true
        if (record.count >= MAX_PER_TITLE) return false
        return now - record.lastAt >= TITLE_COOLDOWN_DAYS * DAY_MS
    }

    fun passesGlobalCooldown(lastGlobalAt: Long, now: Long): Boolean =
        lastGlobalAt <= 0L || now - lastGlobalAt >= GLOBAL_COOLDOWN_DAYS * DAY_MS

    /** Из подходящих берём самый «вовлечённый» тайтл, при равенстве тот, что бросили позже. */
    fun pick(candidates: List<AbandonedCandidate>): AbandonedCandidate? =
        candidates.maxWithOrNull(compareBy({ it.doneCount }, { it.lastActivityAt }))
}
