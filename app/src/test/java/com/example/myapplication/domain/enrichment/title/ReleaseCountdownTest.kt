package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.enrichment.AniLibriaScheduleItem
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.TmdbEnrichment
import com.example.myapplication.network.enrichment.TmdbEpisodeRef
import com.example.myapplication.network.enrichment.TvMazeEpisode
import com.example.myapplication.network.enrichment.TvMazeShow
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила секции «Следующий эпизод через» — по одному тесту на правило из постановки. */
class ReleaseCountdownTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-09-26T12:00:00Z")

    private fun schedule(
        track: ReleaseTrack,
        previous: ReleasePoint? = ReleasePoint(at = now.minus(Duration.ofDays(3))),
        next: ReleasePoint? = ReleasePoint(at = now.plus(Duration.ofDays(4))),
        finished: Boolean = false,
        source: EnrichmentSource = EnrichmentSource.TVMAZE,
    ) = TrackSchedule(track, previous, next, nextEpisode = 5, finished = finished, source = source)

    private fun select(language: AppLanguage, vararg schedules: TrackSchedule, at: Instant = now) =
        ReleaseCountdownRules.select(language, schedules.toList(), at, moscow)

    // 1. Показывается, только если прошлая серия трека вышла не больше 14 дней назад.
    @Test fun `rule 1 - previous episode within 14 days`() {
        assertTrue(select(AppLanguage.RU, schedule(ReleaseTrack.RU, previous = ReleasePoint(at = now.minus(Duration.ofDays(14))))) != null)
        assertNull(select(AppLanguage.RU, schedule(ReleaseTrack.RU, previous = ReleasePoint(at = now.minus(Duration.ofDays(14)).minusSeconds(1)))))
        assertNull("прошлая серия неизвестна", select(AppLanguage.RU, schedule(ReleaseTrack.RU, previous = null)))
    }

    // 2. Русский интерфейс — RU-трек.
    @Test fun `rule 2 - RU language uses RU track`() {
        val ru = schedule(ReleaseTrack.RU, next = ReleasePoint(at = now.plus(Duration.ofDays(2))), source = EnrichmentSource.ANILIBRIA)
        val en = schedule(ReleaseTrack.EN, next = ReleasePoint(at = now.plus(Duration.ofDays(1))))
        val r = select(AppLanguage.RU, en, ru)!!
        assertEquals(ReleaseTrack.RU, r.track)
        assertEquals(now.plus(Duration.ofDays(2)), r.at)
    }

    // 3. Английский интерфейс — EN-трек.
    @Test fun `rule 3 - EN language uses EN track`() {
        val ru = schedule(ReleaseTrack.RU, next = ReleasePoint(at = now.plus(Duration.ofDays(2))))
        val en = schedule(ReleaseTrack.EN, next = ReleasePoint(at = now.plus(Duration.ofDays(1))))
        val r = select(AppLanguage.EN, ru, en)!!
        assertEquals(ReleaseTrack.EN, r.track)
        assertEquals(now.plus(Duration.ofDays(1)), r.at)
    }

    // 4. Трека языка нет — эфир оригинала, помеченный как оригинал; трек языка всегда важнее.
    @Test fun `rule 4 - original broadcast stands in for a missing track, marked as original`() {
        val onlyEn = select(AppLanguage.RU, schedule(ReleaseTrack.EN))!!
        assertTrue("есть только EN — русский интерфейс видит эфир оригинала", onlyEn.original)
        assertEquals(ReleaseTrack.EN, onlyEn.track)
        assertTrue(select(AppLanguage.EN, schedule(ReleaseTrack.RU))!!.original)
        val ru = select(AppLanguage.RU, schedule(ReleaseTrack.EN), schedule(ReleaseTrack.RU, source = EnrichmentSource.ANILIBRIA))!!
        assertFalse("свой трек есть — он и показывается", ru.original)
        assertEquals(ReleaseTrack.RU, ru.track)
        val japanese = TvMazeShow(1, "x", "Running", "Japanese", null, null, null, null,
            TvMazeEpisode(1, 4, null, null, now.minus(Duration.ofDays(3)), 24), TvMazeEpisode(1, 5, null, null, now.plus(Duration.ofDays(4)), 24))
        assertEquals("японский эфир — трек оригинала", ReleaseTrack.ORIGINAL, ReleaseCountdownRules.fromTvMaze(japanese)!!.track)
        assertEquals(ReleaseTrack.ORIGINAL, ReleaseCountdownRules.fromTmdb(tmdb("ja"))!!.track)
        assertEquals(ReleaseTrack.EN, ReleaseCountdownRules.fromTvMaze(japanese.copy(language = "English"))!!.track)
        assertEquals(ReleaseTrack.RU, ReleaseCountdownRules.fromTmdb(tmdb("ru"))!!.track)
    }

    @Test fun `anilist airing schedule is the original track with an exact time`() {
        val next = now.plus(Duration.ofHours(30))
        val media = com.example.myapplication.network.AniListTitleEnrichment(
            anilistId = 1, malId = null, status = "RELEASING", episodes = 12, episodeDurationMin = 24,
            nextEpisode = 5, nextAiringAtEpochSec = next.epochSecond,
            trailerSite = null, trailerId = null, trailerThumbnail = null,
        )
        val r = select(AppLanguage.EN, ReleaseCountdownRules.fromAniList(media, now)!!)!!
        assertEquals(next, r.at)
        assertTrue(r.exactTime)
        assertTrue(r.original)
        assertEquals(5, r.episode)
        assertNull("премьера: прошлой серии нет", ReleaseCountdownRules.fromAniList(media.copy(nextEpisode = 1), now)
            ?.let { select(AppLanguage.EN, it) })
        assertNull("сезон не выходит", ReleaseCountdownRules.fromAniList(media.copy(status = "FINISHED"), now))
    }

    // 5. Известны дата и время — берётся точный момент.
    @Test fun `rule 5 - exact date and time is used as is`() {
        val at = Instant.parse("2026-09-30T17:45:00Z")
        val r = select(AppLanguage.EN, schedule(ReleaseTrack.EN, next = ReleasePoint(at = at, date = LocalDate.parse("2026-09-30"))))!!
        assertEquals(at, r.at)
        assertTrue(r.exactTime)
    }

    // 6. Известна только дата — 12:00 по часовому поясу устройства.
    @Test fun `rule 6 - date only means noon in the device time zone`() {
        val next = ReleasePoint(date = LocalDate.parse("2026-09-30"))
        val moscowNoon = ReleaseCountdownRules.select(AppLanguage.RU, listOf(schedule(ReleaseTrack.RU, next = next)), now, moscow)!!
        assertEquals(Instant.parse("2026-09-30T09:00:00Z"), moscowNoon.at)
        assertFalse(moscowNoon.exactTime)
        val tokyoNoon = ReleaseCountdownRules.select(AppLanguage.RU, listOf(schedule(ReleaseTrack.RU, next = next)), now, ZoneId.of("Asia/Tokyo"))!!
        assertEquals(Instant.parse("2026-09-30T03:00:00Z"), tokyoNoon.at)
        // Прошлая серия с одной датой тоже считается от полудня.
        assertEquals(Instant.parse("2026-09-20T09:00:00Z"), ReleasePoint(date = LocalDate.parse("2026-09-20")).instant(moscow))
    }

    // 7. Отсчёт — от текущего времени устройства.
    @Test fun `rule 7 - countdown is measured from the device clock`() = runBlocking {
        val target = now.plus(Duration.ofHours(2))
        var clock = now.toEpochMilli()
        val ticks = mutableListOf<Duration>()
        runCountdown(target, { clock }, { clock += it; if (ticks.size >= 1) clock = target.toEpochMilli() }) { ticks += it }
        assertEquals(Duration.ofHours(2), ticks.first())
        // Часы устройства на час вперёд — осталось на час меньше.
        val shifted = mutableListOf<Duration>()
        var later = now.plus(Duration.ofHours(1)).toEpochMilli()
        runCountdown(target, { later }, { later = target.toEpochMilli() }) { shifted += it }
        assertEquals(Duration.ofHours(1), shifted.single())
    }

    // 8. Секунды обновляются каждую секунду.
    @Test fun `rule 8 - ticks once per second`() = runBlocking {
        var clock = now.toEpochMilli()
        val target = now.plusSeconds(5)
        val sleeps = mutableListOf<Long>()
        val shown = mutableListOf<String?>()
        runCountdown(target, { clock }, { sleeps += it; clock += it }) { shown += countdownText(it, ru = true) }
        assertEquals(List(5) { 1000L }, sleeps)
        assertEquals(listOf("0 мин 05 сек", "0 мин 04 сек", "0 мин 03 сек", "0 мин 02 сек", "0 мин 01 сек"), shown)
    }

    // 9. Время вышло — отсчёт останавливается, отрицательных значений нет.
    @Test fun `rule 9 - no negative values, countdown stops`() = runBlocking {
        var clock = now.toEpochMilli()
        val target = now.plusMillis(2_500)
        val ticks = mutableListOf<Duration>()
        runCountdown(target, { clock }, { clock += it }) { ticks += it }
        assertTrue(ticks.all { !it.isNegative && !it.isZero })
        assertEquals(target.toEpochMilli(), clock)
        assertNull(countdownText(Duration.ZERO, ru = true))
        assertNull(countdownText(Duration.ofSeconds(-5), ru = true))
        // Уже прошедшая «следующая» серия — секции нет (до обновления расписания).
        assertNull(select(AppLanguage.RU, schedule(ReleaseTrack.RU, next = ReleasePoint(at = now.minusSeconds(1)))))
        assertNull(select(AppLanguage.RU, schedule(ReleaseTrack.RU, next = ReleasePoint(at = now))))
    }

    // 10. Закончен / следующая неизвестна / прошлая старше 14 дней — секции нет.
    @Test fun `rule 10 - finished, unknown next or stale previous hide the section`() {
        assertNull(select(AppLanguage.RU, schedule(ReleaseTrack.RU, finished = true)))
        assertNull(select(AppLanguage.RU, schedule(ReleaseTrack.RU, next = null)))
        assertNull(select(AppLanguage.RU, schedule(ReleaseTrack.RU, previous = ReleasePoint(at = now.minus(Duration.ofDays(20))))))
        // Менее точный источник не «воскрешает» то, что точный признал устаревшим.
        val stale = schedule(ReleaseTrack.EN, previous = ReleasePoint(at = now.minus(Duration.ofDays(30))))
        val fresh = schedule(ReleaseTrack.EN, source = EnrichmentSource.TMDB)
        assertNull(select(AppLanguage.EN, stale, fresh))
        assertTrue(ReleaseCountdownRules.fromTmdb(tmdb("en", status = "Ended"))!!.finished)
    }

    // 11. Формат.
    @Test fun `rule 11 - format`() {
        val full = Duration.ofDays(4).plusHours(16).plusMinutes(33)
        assertEquals("4 дн 16 ч 33 мин 00 сек", countdownText(full, ru = true))
        assertEquals("16 ч 33 мин 00 сек", countdownText(Duration.ofHours(16).plusMinutes(33), ru = true))
        assertEquals("33 мин 00 сек", countdownText(Duration.ofMinutes(33), ru = true))
        assertEquals("4 дн 0 ч 5 мин 07 сек", countdownText(Duration.ofDays(4).plusMinutes(5).plusSeconds(7), ru = true))
        assertEquals("4 d 16 h 33 min 00 sec", countdownText(full, ru = false))
    }

    // AniLibria: день недели выпуска озвучки → дата без времени.
    @Test fun `anilibria dub schedule becomes the RU track`() {
        val saturday = LocalDate.parse("2026-09-26")
        val item = AniLibriaScheduleItem(1, 62331, 62331, ongoing = true, publishDay = DayOfWeek.MONDAY, nextEpisode = 26,
            lastEpisode = 25, lastReleasedAt = Instant.parse("2026-09-21T15:00:00Z"))
        val s = ReleaseCountdownRules.fromAniLibria(item, saturday, moscow)
        assertEquals(ReleasePoint(date = LocalDate.parse("2026-09-28")), s.next)
        val r = ReleaseCountdownRules.select(AppLanguage.RU, listOf(s), now, moscow)!!
        assertEquals(Instant.parse("2026-09-28T09:00:00Z"), r.at)
        assertEquals(26, r.episode)
        // Серия вышла сегодня, в день выпуска — следующая через неделю.
        val today = item.copy(lastReleasedAt = Instant.parse("2026-09-28T08:00:00Z"))
        assertEquals(LocalDate.parse("2026-10-05"), ReleaseCountdownRules.fromAniLibria(today, LocalDate.parse("2026-09-28"), moscow).next!!.date)
        assertTrue(ReleaseCountdownRules.fromAniLibria(item.copy(ongoing = false), saturday, moscow).finished)
        assertNull(ReleaseCountdownRules.fromAniLibria(item.copy(nextEpisode = null), saturday, moscow).next)
    }

    private fun tmdb(lang: String, status: String = "Returning Series") = TmdbEnrichment(
        logos = emptyList(), backdrops = emptyList(), videos = emptyList(), imdbId = null, tvdbId = null, status = status,
        nextEpisode = TmdbEpisodeRef(1, 5, LocalDate.parse("2026-09-30")),
        lastEpisode = TmdbEpisodeRef(1, 4, LocalDate.parse("2026-09-23")),
        originalLanguage = lang,
    )
}
