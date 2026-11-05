package com.example.myapplication.support

import com.example.myapplication.support.SupportReminderPolicy.BASE_INTERVAL
import com.example.myapplication.support.SupportReminderPolicy.DAY
import com.example.myapplication.support.SupportReminderPolicy.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Сценарии напоминания о поддержке на подменённых часах — без ожидания реальных суток.
 * Время — «доверенные» миллисекунды; сутки — номер дня от нуля.
 */
class SupportReminderPolicyTest {

    private val p = SupportReminderPolicy
    private val rnd = Random(7)
    private val t0 = 1_000L * DAY

    private fun day(now: Long) = now / DAY

    /** Сессия: вход, [minutes] переднего плана, уход в фон. */
    private fun SupportReminderState.session(at: Long, minutes: Long): SupportReminderState {
        val s = p.onForeground(this, at, day(at), rnd)
        return p.onBackground(s, at + minutes * MINUTE)
    }

    private fun SupportReminderState.points(n: Int, at: Long): SupportReminderState =
        (1..n).fold(this) { s, i -> p.onEvent(s, SupportActivityEvent.TitleAdded("t$at-$i"), day(at)) }

    /** Состояние, готовое к первому показу: 4 дня, 3 активные сессии, очки выше порога. */
    private fun ready(): Pair<SupportReminderState, Long> {
        var s = SupportReminderState().session(t0, 1) // установка — не активность
        var now = t0
        repeat(3) { i ->
            now = t0 + (i + 1) * DAY + 3 * 3600_000L
            s = s.session(now, 5)
        }
        now = t0 + 4 * DAY
        s = s.points(10, now) // 30 очков за добавления
        s = p.onForeground(s, now, day(now), rnd)
        s = p.countSession(s, now + 3 * MINUTE)
        return s to now + 3 * MINUTE
    }

    @Test
    fun `first install is not activity and nothing shows during the first three days`() {
        var s = SupportReminderState().session(t0, 30)
        assertEquals(0, s.activeSessions)
        assertEquals(0, s.activityScore)
        var now = t0
        repeat(2) { i ->
            now = t0 + (i + 1) * DAY - 3600_000L
            s = s.session(now, 10).points(15, now)
        }
        s = p.onForeground(s, now + 20 * MINUTE, day(now), rnd)
        s = p.countSession(s, now + 25 * MINUTE)
        assertTrue(s.activityScore >= 30)
        assertFalse(p.shouldShow(s, now + 25 * MINUTE, safeContext = true))
    }

    @Test
    fun `threshold reached after three days shows once per session and resets score`() {
        val (s, now) = ready()
        assertTrue(p.shouldShow(s, now, safeContext = true))
        val shown = p.onShown(s, now, rnd)
        assertEquals(0, shown.activityScore)
        assertTrue(shown.requiredScore in 20..30)
        assertFalse("второй показ в той же сессии", p.shouldShow(shown.points(20, now), now + MINUTE, true))
    }

    @Test
    fun `three days alone never trigger a reminder`() {
        var s = SupportReminderState().session(t0, 1)
        val now = t0 + 30 * DAY
        s = s.session(now - 3 * DAY, 5).session(now - 2 * DAY, 5).session(now - DAY, 5)
        s = p.countSession(p.onForeground(s, now, day(now), rnd), now + 3 * MINUTE)
        assertTrue(s.activityScore < 20)
        assertFalse(p.shouldShow(s, now + 3 * MINUTE, true))
    }

    @Test
    fun `unsafe context keeps the reminder pending until a calm moment`() {
        val (s, now) = ready()
        assertFalse("идёт видео / чтение / ввод", p.shouldShow(s, now, safeContext = false))
        assertTrue("следующий спокойный момент", p.shouldShow(s, now + MINUTE, safeContext = true))
    }

    @Test
    fun `not shown right after launch, the session must be active first`() {
        val (ready, now) = ready()
        val later = now + 10 * DAY
        var s = p.onBackground(ready, now + MINUTE)
        s = p.onForeground(s, later, day(later), rnd)
        assertFalse(p.shouldShow(s, later + 10_000L, true))
        s = p.countSession(s, later + 2 * MINUTE)
        assertTrue(p.shouldShow(s, later + 2 * MINUTE, true))
    }

    @Test
    fun `quick restarts continue the same session and do not reopen it`() {
        val (ready, now) = ready()
        var s = p.onShown(ready, now, rnd)
        // процесс убит на переднем плане → новый процесс через 5 минут
        s = p.afterProcessStart(s.copy(clockEffective = now + MINUTE))
        s = p.onForeground(s, now + 6 * MINUTE, day(now), rnd)
        assertTrue(s.shownThisSession)
        assertFalse(p.shouldShow(s.points(30, now), now + 7 * MINUTE, true))
        // через 20 минут — уже новая сессия
        s = p.onBackground(s, now + 8 * MINUTE)
        s = p.onForeground(s, now + 30 * MINUTE, day(now), rnd)
        assertFalse(s.shownThisSession)
    }

    @Test
    fun `consecutive dismissals stretch the interval 3 6 12 24 days`() {
        var (s, now) = ready()
        val gaps = mutableListOf<Long>()
        repeat(5) {
            s = p.onDismissed(p.onShown(s, now, rnd), now)
            gaps += (s.nextEligibleAt - now) / DAY
            now = s.nextEligibleAt
            s = p.onBackground(s, now)
            s = p.onForeground(s, now + 20 * MINUTE, day(now), rnd)
        }
        assertEquals(listOf(3L, 6L, 12L, 24L, 24L), gaps)
    }

    @Test
    fun `positive action resets the dismissal streak`() {
        var (s, now) = ready()
        s = p.onDismissed(p.onShown(s, now, rnd), now)
        s = p.onDismissed(s, now)
        assertEquals(2, s.consecutiveDismissals)
        s = p.onDonate(s, now)
        assertEquals(0, s.consecutiveDismissals)
    }

    @Test
    fun `github is offered once, donate keeps working`() {
        val (s, now) = ready()
        assertEquals(listOf(SupportOption.DONATE, SupportOption.GITHUB), p.offers(s))
        val after = p.onGithub(p.onShown(s, now, rnd), now)
        assertTrue(after.githubStarActionClicked)
        assertEquals(listOf(SupportOption.DONATE), p.offers(after))
        assertEquals(now + 30 * DAY, after.nextEligibleAt)
    }

    @Test
    fun `donate click without payment keeps github and waits 30 days`() {
        val (s, now) = ready()
        val after = p.onDonate(p.onShown(s, now, rnd), now)
        assertFalse(after.donationConfirmed)
        assertEquals(listOf(SupportOption.DONATE, SupportOption.GITHUB), p.offers(after))
        assertEquals(now + 30 * DAY, after.nextEligibleAt)
    }

    @Test
    fun `confirmed donation silences reminders for at least 90 days`() {
        val (s, now) = ready()
        var after = p.onDonationConfirmed(p.onShown(s, now, rnd), now)
        val later = now + 60 * DAY
        after = p.countSession(p.onForeground(p.onBackground(after, now), later, day(later), rnd), later + 3 * MINUTE)
        after = after.points(20, later)
        assertFalse(p.shouldShow(after, later + 3 * MINUTE, true))
        assertTrue(p.shouldShow(after, now + 91 * DAY, true))
    }

    @Test
    fun `no options left means no banner`() {
        val (s, now) = ready()
        assertFalse(p.shouldShow(s.copy(donateAvailable = false, githubStarActionClicked = true), now, true))
    }

    @Test
    fun `same event never pays twice and episode points are capped per day`() {
        var s = SupportReminderState().session(t0, 1)
        val d = day(t0)
        val add = SupportActivityEvent.TitleAdded("shikimori:1")
        s = p.onEvent(p.onEvent(s, add, d), add, d)
        assertEquals(3, s.activityScore)
        repeat(25) { s = p.onEvent(s, SupportActivityEvent.EpisodeWatched("a", 1, it + 1), d) }
        assertEquals(3 + 10, s.activityScore)
        s = p.onEvent(s, SupportActivityEvent.EpisodeWatched("a", 2, 1), d + 1)
        assertEquals(14, s.activityScore)
        s = p.onEvent(s, SupportActivityEvent.SeasonCompleted("a", 1), d + 1)
        s = p.onEvent(s, SupportActivityEvent.TitleCompleted("a"), d + 1)
        s = p.onEvent(s, SupportActivityEvent.TitleCompleted("a"), d + 1)
        assertEquals(14 + 4 + 5, s.activityScore)
    }

    @Test
    fun `uninitialized or corrupted state never shows`() {
        assertFalse(p.shouldShow(SupportReminderState(activityScore = 999, activeSessions = 99, sessionCounted = true), t0, true))
    }

    @Test
    fun `turning the clock back does not shorten cooldown, forward jumps are capped by uptime`() {
        var s = TrustedClock.advance(SupportReminderState(), wall = t0, elapsed = 1_000L, boot = 5)
        // назад на неделю: эффективное время стоит
        s = TrustedClock.advance(s, wall = t0 - 7 * DAY, elapsed = 61_000L, boot = 5)
        assertEquals(t0, s.clockEffective)
        // вперёд на месяц за минуту реального времени: прирост ограничен uptime + допуск
        s = TrustedClock.advance(s, wall = t0 + 30 * DAY, elapsed = 121_000L, boot = 5)
        assertTrue(s.clockEffective - t0 <= 2 * MINUTE)
        // после перезагрузки сверять не с чем — берём прирост часов, но не отрицательный
        val rebooted = TrustedClock.advance(s, wall = t0 + 31 * DAY, elapsed = 5_000L, boot = 6)
        assertTrue(rebooted.clockEffective > s.clockEffective)
        assertEquals(rebooted.clockEffective, TrustedClock.advance(rebooted, t0, 6_000L, 7).clockEffective)
    }

    @Test
    fun `base interval is three days`() {
        assertEquals(3 * DAY, BASE_INTERVAL)
    }
}
