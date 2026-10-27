package com.example.myapplication.domain.recommendations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun mix(anime: Int, screen: Int, movieShare: Float = 0.5f): ContentMix {
    val movies = (screen * movieShare).toInt()
    return ContentMix(anime = anime, movies = movies, series = screen - movies)
}

class ContentMixResolverTest {

    private fun slots(anime: Int, screen: Int) = ContentMixResolver.resolve(mix(anime, screen)).let { it.anime to (it.movies + it.series) }

    @Test
    fun `table from the plan holds for 20 cards`() {
        assertEquals(20 to 0, slots(100, 0))
        assertEquals(20 to 0, slots(90, 10))
        assertEquals(16 to 4, slots(80, 20))
        assertEquals(14 to 6, slots(70, 30))
        assertEquals(12 to 8, slots(60, 40))
        assertEquals(10 to 10, slots(50, 50))
        assertEquals(8 to 12, slots(40, 60))
        assertEquals(0 to 20, slots(10, 90))
    }

    @Test
    fun `empty library starts neutral`() {
        val q = ContentMixResolver.resolve(ContentMix())
        assertEquals(10, q.anime)
        assertEquals(10, q.movies + q.series)
        assertEquals(20, q.total)
    }

    @Test
    fun `a single title does not make the whole feed one type`() {
        val q = ContentMixResolver.resolve(ContentMix(anime = 1))
        assertTrue("anime ${q.anime}", q.anime in 11..17)
        assertTrue(q.movies + q.series >= 3)
    }

    @Test
    fun `screen slots follow the movie to series split`() {
        val q = ContentMixResolver.resolve(ContentMix(anime = 0, movies = 70, series = 30))
        assertEquals(Quota(0, 14, 6), q)
    }

    @Test
    fun `hysteresis keeps a dominant group near the threshold`() {
        val nearThreshold = mix(82, 18)
        val fresh = ContentMixResolver.resolve(nearThreshold)
        assertTrue("without history it is mixed", fresh.anime < 20)
        val sticky = ContentMixResolver.resolve(nearThreshold, previous = Quota(20, 0, 0))
        assertEquals(20, sticky.anime)
    }

    @Test
    fun `slots of a type without candidates go to the others`() {
        val available = mapOf(ContentType.ANIME to 3, ContentType.MOVIE to 50, ContentType.TV to 50)
        val q = ContentMixResolver.resolve(mix(50, 50), available)
        assertEquals(3, q.anime)
        assertEquals(20, q.total)
    }

    @Test
    fun `quota never exceeds what exists`() {
        val available = mapOf(ContentType.ANIME to 4, ContentType.MOVIE to 2, ContentType.TV to 1)
        val q = ContentMixResolver.resolve(mix(50, 50), available)
        assertEquals(7, q.total)
    }

    @Test
    fun `interleave mixes types instead of two big blocks`() {
        val pools = mapOf(
            ContentType.ANIME to (1..6).map { "a$it" },
            ContentType.MOVIE to (1..6).map { "m$it" },
        )
        val deck = QuotaAllocator.interleave(pools, Quota(6, 6, 0))
        assertEquals(12, deck.size)
        // Ни одного типа подряд больше двух раз.
        assertTrue(deck.windowed(3).none { w -> w.all { it.startsWith("a") } || w.all { it.startsWith("m") } })
        // Внутри типа порядок ранга сохранён.
        assertEquals((1..6).map { "a$it" }, deck.filter { it.startsWith("a") })
    }
}
