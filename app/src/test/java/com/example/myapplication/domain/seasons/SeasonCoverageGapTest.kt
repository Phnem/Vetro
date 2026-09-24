package com.example.myapplication.domain.seasons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правило, по которому приложение само замечает, что расклад сезонов отстал.
 *
 * Сеть и резолверы на JVM не проверить, а вот «пуш про 5-ю серию 3-го сезона против списка из двух
 * сезонов — это расхождение» проверить обязательно: именно на этом решении висит, полезет ли
 * фоновый проход в источники. Ошибка в любую сторону дорогая — либо пользователь снова жмёт
 * «Найти ещё» руками, либо приложение ходит по сайтам на каждом проходе впустую.
 */
class SeasonCoverageGapTest {

    private fun season(number: Int, episodes: Int) =
        SeasonInfo(seasonNumber = number, episodes = episodes, source = "AniList")

    @Test
    fun `airing season missing from the layout is a gap`() {
        val gap = seasonCoverageGap(
            seasons = listOf(season(1, 12), season(2, 12)),
            airingSeason = 3,
            airedEpisodes = 5,
            franchiseEpisodes = 24,
        )

        assertNotNull(gap)
        assertEquals(3, gap!!.missingSeason)
        assertEquals(5, gap.missingEpisodes)
    }

    @Test
    fun `airing season present but short of the aired count is a gap`() {
        val gap = seasonCoverageGap(
            seasons = listOf(season(1, 12), season(2, 12), season(3, 1)),
            airingSeason = 3,
            airedEpisodes = 10,
            franchiseEpisodes = 25,
        )

        assertNotNull(gap)
        assertNull(gap!!.missingSeason)
        assertEquals(9, gap.missingEpisodes)
    }

    @Test
    fun `layout that already covers the airing season is not a gap`() {
        assertNull(
            seasonCoverageGap(
                seasons = listOf(season(1, 12), season(2, 12), season(3, 10)),
                airingSeason = 3,
                airedEpisodes = 10,
                franchiseEpisodes = 34,
            ),
        )
    }

    @Test
    fun `collection counter above the layout sum is a gap`() {
        // Пользователь ведёт запись по всей франшизе (авто-применение проставило 40 серий), а в
        // раскладе один сезон — ровно случай «открыл после пуша, а там один сезон».
        val gap = seasonCoverageGap(
            seasons = listOf(season(1, 12)),
            airingSeason = null,
            airedEpisodes = null,
            franchiseEpisodes = 40,
        )

        assertEquals(28, gap?.missingEpisodes)
    }

    @Test
    fun `collection counter below the layout sum is not a gap`() {
        // Обычный случай: запись ведётся по одному сезону, расклад знает всю франшизу.
        assertNull(
            seasonCoverageGap(
                seasons = listOf(season(1, 12), season(2, 12)),
                airingSeason = null,
                airedEpisodes = null,
                franchiseEpisodes = 12,
            ),
        )
    }

    @Test
    fun `specials do not count towards the season scale`() {
        // Спецвыпуск стоит пятой строкой с номером 3, но сезон он не закрывает и его серии в
        // счётчик франшизы не входят — иначе OVA маскировала бы отсутствие настоящего сезона.
        val special = SeasonInfo(
            seasonNumber = 3,
            episodes = 4,
            source = "AniList",
            kind = SeasonKind.Special,
            format = "OVA",
        )
        val gap = seasonCoverageGap(
            seasons = listOf(season(1, 12), season(2, 12), special),
            airingSeason = 3,
            airedEpisodes = 5,
            franchiseEpisodes = null,
        )

        assertEquals(3, gap?.missingSeason)
    }

    @Test
    fun `empty layout waits for the resolver instead of reporting a gap`() {
        // Пустой расклад — это «ещё не резолвили», а не «отстал»: его заполняют ensureResolved и
        // refreshStale, и объявлять здесь пробел значило бы дублировать ту же работу опросом
        // источников просмотра — самым дорогим шагом каскада.
        assertNull(
            seasonCoverageGap(
                seasons = emptyList(),
                airingSeason = 3,
                airedEpisodes = 5,
                franchiseEpisodes = 40,
            ),
        )
    }
}
