package com.example.myapplication.localplayer.domain

import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.domain.seasons.SeasonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FranchiseChainFromLayoutTest {

    private fun season(n: Int, eps: Int, al: Int?, mal: Int?, total: Int? = null, special: Boolean = false) =
        SeasonInfo(
            seasonNumber = n,
            episodes = eps,
            totalEpisodes = total,
            anilistId = al,
            malId = mal,
            source = "AniList",
            kind = if (special) SeasonKind.Special else SeasonKind.Season,
        )

    private val layout = listOf(
        season(2, 12, al = 102, mal = 20),
        season(1, 24, al = 101, mal = 10),
        season(1, 1, al = 150, mal = 55, special = true), // OVA вне сезонной нумерации
    )

    @Test
    fun chain_is_ordered_by_season_and_skips_specials() {
        val stored = chainFromLayout(layout, anilistId = 101, malId = null)!!
        assertEquals(listOf(FranchiseSeason(10, 24), FranchiseSeason(20, 12)), stored.chain)
        assertEquals(FranchiseSeason(10, 24), stored.self)
        // Абсолютная 26-я серия франшизы — вторая серия второго сезона, как при обходе сети.
        assertEquals(20 to 2, offsetAbsoluteEpisode(stored.chain, 26))
    }

    @Test
    fun matches_by_mal_id_too() {
        assertEquals(FranchiseSeason(20, 12), chainFromLayout(layout, anilistId = null, malId = 20)!!.self)
    }

    @Test
    fun announced_total_wins_over_aired_for_offsets() {
        val ongoing = listOf(season(1, 8, al = 1, mal = 11, total = 12), season(2, 3, al = 2, mal = 22))
        assertEquals(12, chainFromLayout(ongoing, 1, null)!!.chain.first().episodes)
    }

    @Test
    fun unusable_layout_falls_back_to_network() {
        // Нет MAL id у сезона — сдвиг не посчитать, маппер должен идти в сеть.
        assertNull(chainFromLayout(listOf(season(1, 12, al = 1, mal = null)), 1, null))
        // Тайтл не из этой франшизы.
        assertNull(chainFromLayout(layout, anilistId = 999, malId = 999))
    }
}
