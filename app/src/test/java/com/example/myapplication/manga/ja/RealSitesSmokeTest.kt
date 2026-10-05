package com.example.myapplication.manga.ja

import com.example.myapplication.manga.domain.MangaSourceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Парсеры на НАСТОЯЩИХ ответах сайтов, сохранённых заранее (`capture.py` в папке разработчика).
 * Без переменной `VETRO_JA_DIR` тест пропускается: файлы с чужого сайта в репозиторий не кладутся.
 */
class RealSitesSmokeTest {

    private val dir: File? = System.getenv("VETRO_JA_DIR")?.let(::File)?.takeIf { it.isDirectory }

    private fun text(name: String) = File(requireNotNull(dir), name).readText()

    @Test
    fun gigaviewer_parsers_read_a_real_search_feed_and_episode() {
        assumeTrue("VETRO_JA_DIR is not set", dir != null)
        val meta = Json.parseToJsonElement(text("giga_meta.json")).jsonObject
        val hits = GigaViewerParser.parseSearch(text("giga_search.html"), "https://shonenjumpplus.com")
        println("giga search hits: " + hits.map { "${it.title} / ${it.author} / ${it.episodeId}" })
        assertTrue("search must find the series", hits.any { JaTitles.same(it.title, "株式会社マジルミエ") })
        assertEquals(meta["first"]!!.jsonPrimitive.content, hits.first { JaTitles.same(it.title, "株式会社マジルミエ") }.episodeId)

        assertEquals(meta["aggregate"]!!.jsonPrimitive.content, GigaViewerParser.parseAggregateId(text("giga_first_episode.html")))

        val rss = GigaViewerParser.parseRss(text("giga_rss.xml"))
        println("giga rss: ${rss.size} items, numbers ${rss.mapNotNull { it.number }.let { "${it.minOrNull()}..${it.maxOrNull()}" }}")
        // В счёте скрипта ссылка канала дублирует первую главу: сравниваем с числом уникальных ссылок.
        assertEquals(rss.size, rss.map { it.episodeId }.distinct().size)
        assertTrue(rss.size >= meta["count"]!!.jsonPrimitive.content.toInt() - 1)
        // Экстры и иллюстрации ("番外編20", "イラスト4") номера главы не имеют - это нормально.
        val numbered = rss.mapNotNull { it.number }
        assertTrue("most items must carry a chapter number", numbered.size > rss.size * 0.7)
        assertTrue("numbering must reach the latest chapters", (numbered.maxOrNull() ?: 0.0) >= 150.0)

        val episode = GigaViewerParser.parseEpisode(text("giga_episode.html"))!!
        println("giga episode: public=${episode.isPublic} scrambled=${episode.scrambled} pages=${episode.pages.size}")
        assertTrue(episode.isPublic)
        assertTrue(episode.pages.size > 5)
    }

    @Test
    fun comicwalker_parsers_read_real_json() {
        assumeTrue("VETRO_JA_DIR is not set", dir != null)
        val meta = Json.parseToJsonElement(text("cw_meta.json")).jsonObject
        val found = ComicWalkerParser.parseSearch(Json.parseToJsonElement(text("cw_search.json")), MangaSourceId("ja-comicwalker"))
        println("cw search: " + found.take(3).map { "${it.key} ${it.title} / ${it.author}" })
        assertTrue(found.any { it.key == meta["code"]!!.jsonPrimitive.content })

        val episodes = ComicWalkerParser.parseEpisodes(Json.parseToJsonElement(text("cw_work.json")))
        println("cw episodes: " + episodes.map { "${it.title} #${it.number}" })
        assertTrue(episodes.isNotEmpty())

        val pages = ComicWalkerParser.parseViewer(Json.parseToJsonElement(text("cw_viewer.json")))
        println("cw viewer: ${pages.size} pages, keys ${pages.map { it.xorKey }.distinct().size}")
        assertTrue(pages.size > 3)
        assertTrue(pages.all { it.xorKey != null && it.url.startsWith("https://") })
    }

    @Test
    fun gangan_parsers_read_real_next_data() {
        assumeTrue("VETRO_JA_DIR is not set", dir != null)
        val catalog = GanganParser.parseCatalog(text("gg_catalog.html"), MangaSourceId("ja-gangan"))
        println("gangan catalog: ${catalog.size} titles")
        assertTrue(catalog.size > 20)
        assertTrue(catalog.any { it.key == "1192" })

        val chapters = GanganParser.parseChapters(GanganParser.nextData(text("gg_title.html"))!!)
        println("gangan chapters: " + chapters.take(6).map { "${it.title} #${it.number}" })
        assertTrue(chapters.isNotEmpty())

        val pages = GanganParser.parsePages(GanganParser.nextData(text("gg_chapter.html"))!!)
        println("gangan pages: ${pages.size}")
        assertTrue(pages.size > 5)
    }
}
