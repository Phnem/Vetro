package com.example.myapplication.support

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/*
 * Напоминание о поддержке Vetro (донат / звезда на GitHub) — чистая логика без Android.
 *
 * Activity Score определяет заинтересованность, cooldown ограничивает частоту, контекст UI — момент показа,
 * реакция пользователя адаптирует дальнейшее поведение. Истечение трёх дней само по себе показ не вызывает.
 * Все функции — чистые преобразования [SupportReminderState]; время — «доверенные» миллисекунды
 * ([TrustedClock]), поэтому тесты подменяют часы и не ждут реальных суток.
 */

/** Что предлагает шторка. */
enum class SupportOption { DONATE, GITHUB }

/** Бизнес-события, которые приносят очки активности. [key] — для дедупликации (синк/повтор не начисляют). */
sealed class SupportActivityEvent(val key: String, val points: Int) {
    class TitleAdded(identity: String) : SupportActivityEvent("add:$identity", 3)
    class EpisodeWatched(animeId: String, season: Int, episode: Int) : SupportActivityEvent("ep:$animeId:$season:$episode", 1)
    class SeasonCompleted(animeId: String, season: Int) : SupportActivityEvent("season:$animeId:$season", 4)
    class TitleCompleted(animeId: String) : SupportActivityEvent("done:$animeId", 5)
}

data class SupportReminderState(
    /** 0 — ещё не инициализировано (первый запуск не наступал). */
    val firstLaunchAt: Long = 0L,
    val lastShownAt: Long? = null,
    val nextEligibleAt: Long = 0L,
    val activityScore: Int = 0,
    val requiredScore: Int = 0,
    val activeSessions: Int = 0,
    val consecutiveDismissals: Int = 0,
    val githubStarActionClicked: Boolean = false,
    val donateAvailable: Boolean = true,
    val donationConfirmed: Boolean = false,
    val donationCooldownUntil: Long? = null,
    val totalImpressions: Int = 0,
    // ── сессии ──
    val sessionStartedAt: Long = 0L,
    /** Накопленное время в переднем плане за сессию (до текущего входа). */
    val sessionForegroundMs: Long = 0L,
    /** Когда приложение вышло на передний план; null — сейчас в фоне. */
    val foregroundSince: Long? = null,
    val lastBackgroundAt: Long? = null,
    /** Сессия уже засчитана активной (≥ 2 минут). */
    val sessionCounted: Boolean = false,
    /** Первая сессия после установки — не считается полноценной активностью. */
    val sessionIsFirst: Boolean = false,
    val shownThisSession: Boolean = false,
    // ── суточные ограничения ──
    /** Номер календарных суток (epochDay в часовом поясе устройства) последнего входа. */
    val lastActiveDay: Long = -1L,
    val episodePointsDay: Long = -1L,
    val episodePointsToday: Int = 0,
    /** Хэши уже засчитанных событий (последние [SupportReminderPolicy.MAX_SEEN]). */
    val seenEvents: List<Int> = emptyList(),
    // ── доверенные часы ──
    val clockEffective: Long = 0L,
    val clockWall: Long = 0L,
    val clockElapsed: Long = 0L,
    val clockBoot: Int = -1,
)

/**
 * Время, которое нельзя открутить: эффективные миллисекунды никогда не идут назад, а в пределах одной
 * загрузки устройства не убегают вперёд быстрее монотонного elapsedRealtime (+ небольшой допуск). Поэтому
 * перевод системных часов не снимает cooldown, а смена часового пояса ничего не меняет (epoch-время).
 */
object TrustedClock {
    private const val SLACK_MS = 60_000L

    fun advance(s: SupportReminderState, wall: Long, elapsed: Long, boot: Int): SupportReminderState {
        if (s.clockWall == 0L) return s.copy(clockEffective = wall, clockWall = wall, clockElapsed = elapsed, clockBoot = boot)
        val wallDelta = wall - s.clockWall
        val delta = if (boot >= 0 && boot == s.clockBoot) {
            wallDelta.coerceIn(0L, max(0L, elapsed - s.clockElapsed) + SLACK_MS)
        } else {
            wallDelta.coerceAtLeast(0L)
        }
        return s.copy(clockEffective = s.clockEffective + delta, clockWall = wall, clockElapsed = elapsed, clockBoot = boot)
    }
}

object SupportReminderPolicy {
    const val MINUTE = 60_000L
    const val DAY = 24 * 60 * MINUTE
    const val BASE_INTERVAL = 3 * DAY
    const val DONATE_COOLDOWN = 30 * DAY
    const val GITHUB_COOLDOWN = 30 * DAY
    const val CONFIRMED_DONATION_COOLDOWN = 90 * DAY
    const val SESSION_CONTINUE_GAP = 15 * MINUTE
    const val ACTIVE_SESSION_MS = 2 * MINUTE
    const val ACTIVE_SESSION_POINTS = 2
    const val NEW_DAY_POINTS = 2
    const val EPISODE_DAILY_CAP = 10
    const val MIN_SESSIONS = 3
    const val SCORE_MIN = 20
    const val SCORE_MAX = 30
    const val MAX_SEEN = 1500

    fun newRequiredScore(random: Random) = random.nextInt(SCORE_MIN, SCORE_MAX + 1)

    /** Доступные варианты поддержки: GitHub — один раз, донат — всегда, пока не выключен. */
    fun offers(s: SupportReminderState): List<SupportOption> = buildList {
        if (s.donateAvailable) add(SupportOption.DONATE)
        if (!s.githubStarActionClicked) add(SupportOption.GITHUB)
    }

    /** Приложение вышло на передний план: первый запуск, продолжение сессии (< 15 мин) или новая сессия. */
    fun onForeground(s: SupportReminderState, now: Long, day: Long, random: Random): SupportReminderState {
        if (s.firstLaunchAt <= 0L) {
            return s.copy(
                firstLaunchAt = now,
                nextEligibleAt = now + BASE_INTERVAL,
                requiredScore = newRequiredScore(random),
                sessionStartedAt = now,
                sessionForegroundMs = 0L,
                foregroundSince = now,
                lastBackgroundAt = null,
                sessionCounted = false,
                sessionIsFirst = true,
                shownThisSession = false,
                lastActiveDay = day,
            )
        }
        if (s.foregroundSince != null) return s // уже на переднем плане (повторный onStart)
        val continuing = s.lastBackgroundAt != null && now - s.lastBackgroundAt <= SESSION_CONTINUE_GAP
        var next = if (continuing) {
            s.copy(foregroundSince = now, lastBackgroundAt = null)
        } else {
            s.copy(
                sessionStartedAt = now,
                sessionForegroundMs = 0L,
                foregroundSince = now,
                lastBackgroundAt = null,
                sessionCounted = false,
                sessionIsFirst = false,
                shownThisSession = false,
            )
        }
        if (day != s.lastActiveDay) {
            next = next.copy(lastActiveDay = day, activityScore = next.activityScore + NEW_DAY_POINTS)
        }
        return next
    }

    /**
     * Новый процесс: если прошлый умер на переднем плане (onStop не пришёл), сессия закрывается на последнем
     * известном времени — иначе следующий вход счёл бы приложение всё ещё открытым. Быстрый перезапуск
     * (< 15 мин) после этого продолжит ту же сессию.
     */
    fun afterProcessStart(s: SupportReminderState): SupportReminderState {
        val since = s.foregroundSince ?: return s
        return onBackground(s, max(since, s.clockEffective))
    }

    /** Ушли в фон: время переднего плана копится в сессию. */
    fun onBackground(s: SupportReminderState, now: Long): SupportReminderState {
        val since = s.foregroundSince ?: return s
        return countSession(
            s.copy(
                sessionForegroundMs = s.sessionForegroundMs + max(0L, now - since),
                foregroundSince = null,
                lastBackgroundAt = now,
            ),
            now,
        )
    }

    /** Засчитывает сессию активной, когда в ней набралось ≥ 2 минут переднего плана. */
    fun countSession(s: SupportReminderState, now: Long): SupportReminderState {
        if (s.sessionCounted || s.sessionIsFirst || s.firstLaunchAt <= 0L) return s
        val fg = s.sessionForegroundMs + (s.foregroundSince?.let { max(0L, now - it) } ?: 0L)
        if (fg < ACTIVE_SESSION_MS) return s
        return s.copy(
            sessionCounted = true,
            activeSessions = s.activeSessions + 1,
            activityScore = s.activityScore + ACTIVE_SESSION_POINTS,
        )
    }

    /** Очки за событие: один раз на ключ, серии — не больше [EPISODE_DAILY_CAP] за сутки. */
    fun onEvent(s: SupportReminderState, event: SupportActivityEvent, day: Long): SupportReminderState {
        if (s.firstLaunchAt <= 0L) return s
        val hash = event.key.hashCode()
        if (hash in s.seenEvents) return s
        var points = event.points
        var next = s.copy(seenEvents = (s.seenEvents + hash).takeLast(MAX_SEEN))
        if (event is SupportActivityEvent.EpisodeWatched) {
            val today = if (s.episodePointsDay == day) s.episodePointsToday else 0
            points = min(points, max(0, EPISODE_DAILY_CAP - today))
            next = next.copy(episodePointsDay = day, episodePointsToday = today + points)
        }
        return if (points > 0) next.copy(activityScore = next.activityScore + points) else next
    }

    /**
     * Можно ли показать напоминание сейчас. [safeContext] — UI в спокойном состоянии (решает вызывающий).
     * Повреждённые или неполные данные трактуются строго: без инициализации — не показываем.
     */
    fun shouldShow(s: SupportReminderState, now: Long, safeContext: Boolean): Boolean {
        if (s.firstLaunchAt <= 0L) return false
        if (offers(s).isEmpty()) return false
        if (s.donationConfirmed && now < (s.donationCooldownUntil ?: Long.MAX_VALUE)) return false
        if (s.shownThisSession) return false
        if (!s.sessionCounted) return false // не сразу после запуска: сессия должна быть активной
        if (s.activeSessions < MIN_SESSIONS) return false
        if (s.activityScore < max(SCORE_MIN, s.requiredScore)) return false
        if (now < s.firstLaunchAt + BASE_INTERVAL) return false
        if (now < s.nextEligibleAt) return false
        return safeContext
    }

    /** Показ состоялся: новый цикл очков и порога; предварительный интервал — на случай, если реакции не будет. */
    fun onShown(s: SupportReminderState, now: Long, random: Random) = s.copy(
        lastShownAt = now,
        totalImpressions = s.totalImpressions + 1,
        shownThisSession = true,
        activityScore = 0,
        requiredScore = newRequiredScore(random),
        nextEligibleAt = max(s.nextEligibleAt, now + BASE_INTERVAL),
    )

    /** Закрыли без действия: 3 → 6 → 12 → 24 дня. */
    fun onDismissed(s: SupportReminderState, now: Long): SupportReminderState {
        val n = s.consecutiveDismissals + 1
        val interval = BASE_INTERVAL shl (min(n, 4) - 1)
        return s.copy(consecutiveDismissals = n, nextEligibleAt = max(s.nextEligibleAt, now + interval))
    }

    /** Перешёл к донату: 30 дней тишины; открытие ссылки — не подтверждение оплаты. */
    fun onDonate(s: SupportReminderState, now: Long) = s.copy(
        consecutiveDismissals = 0,
        nextEligibleAt = max(s.nextEligibleAt, now + DONATE_COOLDOWN),
    )

    /** Звезда на GitHub — предложение принято навсегда (проверять, поставлена ли звезда, не нужно). */
    fun onGithub(s: SupportReminderState, now: Long) = s.copy(
        githubStarActionClicked = true,
        consecutiveDismissals = 0,
        nextEligibleAt = max(s.nextEligibleAt, now + GITHUB_COOLDOWN),
    )

    /** Достоверное подтверждение платежа: минимум 90 дней без напоминаний. */
    fun onDonationConfirmed(s: SupportReminderState, now: Long) = s.copy(
        donationConfirmed = true,
        donationCooldownUntil = now + CONFIRMED_DONATION_COOLDOWN,
        consecutiveDismissals = 0,
        nextEligibleAt = max(s.nextEligibleAt, now + CONFIRMED_DONATION_COOLDOWN),
    )
}
