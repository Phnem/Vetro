package com.example.myapplication.domain.seasons

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingSeasonRefreshTest {

    @Test
    fun `repeated find more replaces stale seasons instead of preserving them`() {
        val stale = listOf(
            SeasonInfo(seasonNumber = 1, episodes = 24, source = "AniList"),
            SeasonInfo(seasonNumber = 2, episodes = 13, source = "AniList"),
            SeasonInfo(seasonNumber = 3, episodes = 12, source = "Kodik"),
        )
        val freshCatalogue = listOf(
            SeasonInfo(
                seasonNumber = 1,
                episodes = 24,
                source = "AniList",
                title = "Food Wars!",
            ),
            SeasonInfo(
                seasonNumber = 2,
                episodes = 13,
                source = "AniList",
                title = "Food Wars! The Second Plate",
            ),
        )
        val freshlyDiscovered = listOf(
            DiscoveredSeason(seasonNumber = 1, episodes = 24, source = "Kodik"),
            DiscoveredSeason(seasonNumber = 2, episodes = 13, source = "Kodik"),
        )

        val refreshed = refreshSeasonDiscovery(stale, freshCatalogue, freshlyDiscovered)

        assertEquals(listOf(1, 2), refreshed.seasons.map { it.seasonNumber })
        assertEquals(1, refreshed.removedSeasons)
        assertEquals(
            "Food Wars! The Second Plate",
            refreshed.seasons.first { it.seasonNumber == 2 }.title,
        )
    }

    @Test
    fun `existing cached list makes find more a full refresh`() {
        val cached = listOf(
            SeasonInfo(seasonNumber = 1, episodes = 24, source = "AniList"),
            SeasonInfo(seasonNumber = 2, episodes = 13, source = "Kodik"),
        )

        assertEquals(
            true,
            shouldRefreshSeasonDiscovery(explicitlyRequested = false, known = cached),
        )
        assertEquals(
            true,
            shouldRefreshSeasonDiscovery(explicitlyRequested = true, known = emptyList()),
        )
        assertEquals(
            true,
            shouldRefreshSeasonDiscovery(
                explicitlyRequested = false,
                known = listOf(
                    SeasonInfo(seasonNumber = 1, episodes = 24, source = "AniList"),
                ),
            ),
        )
    }

    @Test
    fun `specials keep their own numbers when a gapped season is discovered`() {
        // Регрессия: номера спецвыпусков когда-то считались от РАЗМЕРА слитого списка, а номера
        // сезонов идут не подряд — источник просмотра добавляет свой пятый сезон к двум известным.
        // Спецвыпуск получал номер существующего сезона, а номер здесь — ключ строки: дубликат
        // роняет LazyColumn на экране серий и склеивает прогресс двух разных вещей.
        val known = listOf(
            SeasonInfo(seasonNumber = 1, episodes = 12, source = "AniList"),
            SeasonInfo(
                seasonNumber = SPECIAL_SEASON_BASE,
                episodes = 4,
                source = "AniList",
                kind = SeasonKind.Special,
                format = "OVA",
            ),
        )
        val discovered = listOf(DiscoveredSeason(seasonNumber = 3, episodes = 10, source = "jut.su"))

        val merged = mergeSeasonDiscovery(known, discovered)

        val numbers = merged.map { it.seasonNumber }
        assertEquals(numbers.distinct(), numbers)
        assertEquals(listOf(1, 3, SPECIAL_SEASON_BASE), numbers)
    }

    @Test
    fun `discovery never overwrites a special row`() {
        // Номер из диапазона спецвыпусков источник выдать не может — это мусор из разбора
        // страницы, и пустив его дальше, мы затёрли бы им строку OVA.
        val known = listOf(
            SeasonInfo(seasonNumber = 1, episodes = 12, source = "AniList"),
            SeasonInfo(
                seasonNumber = SPECIAL_SEASON_BASE,
                episodes = 4,
                source = "AniList",
                kind = SeasonKind.Special,
                format = "OVA",
            ),
        )
        val discovered = listOf(
            DiscoveredSeason(seasonNumber = SPECIAL_SEASON_BASE, episodes = 99, source = "Kodik"),
        )

        val merged = mergeSeasonDiscovery(known, discovered)

        val special = merged.single { it.isSpecial }
        assertEquals(4, special.episodes)
        assertEquals("AniList", special.source)
        assertEquals(2, merged.size)
    }

    @Test
    fun `a special is asked of a source as a standalone release`() {
        // AniLibria берёт релиз ИНДЕКСОМ по цепочке франшизы: технический номер спецвыпуска
        // молча вернул бы чужой сезон.
        val special = SeasonInfo(
            seasonNumber = SPECIAL_SEASON_BASE + 2,
            episodes = 1,
            source = "AniList",
            kind = SeasonKind.Special,
            format = "MOVIE",
        )

        assertEquals(1, special.forSourceLookup().seasonNumber)
        // Обычный сезон нормализация не трогает.
        val season = SeasonInfo(seasonNumber = 3, episodes = 12, source = "AniList")
        assertEquals(3, season.forSourceLookup().seasonNumber)
    }

}
