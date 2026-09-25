package com.example.myapplication.media.source

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Выбор записи AnimeHeaven по названию сезона из каталога. Запись коллекции «Джо-Джо» называется
 * STONE OCEAN (titleEn), и раньше её первый сезон резолвился в «Stone Ocean – Part III».
 */
class AnimeHeavenSeasonTitleTest {

    private val source = AnimeHeavenSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))

    private val franchise = listOf(
        AnimeHeavenSource.Entry("1", "JoJo's Bizarre Adventure: Stone Ocean - Part III"),
        AnimeHeavenSource.Entry("2", "JoJo's Bizarre Adventure: Stone Ocean"),
        AnimeHeavenSource.Entry("3", "JoJo's Bizarre Adventure: Stardust Crusaders - Battle in Egypt"),
        AnimeHeavenSource.Entry("4", "JoJo's Bizarre Adventure: Stardust Crusaders"),
        AnimeHeavenSource.Entry("5", "JoJo's Bizarre Adventure"),
        AnimeHeavenSource.Entry("6", "JoJo's Bizarre Adventure: Golden Wind"),
    )

    @Test
    fun `first season picks the franchise root, not the record's own arc`() {
        assertEquals("5", source.pickForSeasonTitle("JoJo's Bizarre Adventure (TV)", franchise)?.id)
    }

    @Test
    fun `arc title picks the exact arc, not its continuation`() {
        assertEquals(
            "4",
            source.pickForSeasonTitle("JoJo's Bizarre Adventure: Stardust Crusaders", franchise)?.id,
        )
        assertEquals(
            "3",
            source.pickForSeasonTitle(
                "JoJo's Bizarre Adventure: Stardust Crusaders - Battle in Egypt",
                franchise,
            )?.id,
        )
    }

    @Test
    fun `a part marker must match - part one never plays part three`() {
        val onlyPartThree = listOf(
            AnimeHeavenSource.Entry("1", "JoJo's Bizarre Adventure: Stone Ocean - Part III"),
            AnimeHeavenSource.Entry("7", "JoJo's Bizarre Adventure: Steel Ball Run"),
        )
        assertEquals(null, source.pickForSeasonTitle("JoJo's Bizarre Adventure: STONE OCEAN", onlyPartThree))
        assertEquals(null, source.pickForSeasonTitle("JoJo's Bizarre Adventure (TV)", onlyPartThree))
        assertEquals(
            "1",
            source.pickForSeasonTitle("JoJo's Bizarre Adventure: STONE OCEAN Part 3", onlyPartThree)?.id,
        )
    }

    @Test
    fun `a sequel entry belongs to the sequel even when the first season title is its subset`() {
        val site = listOf(
            AnimeHeavenSource.Entry("t", "Food Wars! The Third Plate"),
            AnimeHeavenSource.Entry("s", "Food Wars! The Second Plate"),
        )
        val layout = listOf(
            "Food Wars! The Second Plate",
            "Food Wars! The Third Plate",
            "Food Wars! The Fourth Plate",
        )
        // Первого сезона на сайте нет — лучше ничего, чем серии третьего.
        assertEquals(null, source.pickForSeasonTitle("Food Wars!", site, layout))
        assertEquals("t", source.pickForSeasonTitle("Food Wars! The Third Plate", site, layout - "Food Wars! The Third Plate" + "Food Wars!")?.id)
    }

    @Test
    fun `unrelated title matches nothing`() {
        assertEquals(null, source.pickForSeasonTitle("Food Wars! The Second Plate", franchise))
    }
}
