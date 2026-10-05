package com.example.myapplication.manga.ja

import com.example.myapplication.manga.domain.MangaSourceId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JaTitlesTest {

    @Test
    fun hiragana_and_katakana_and_width_forms_are_the_same_title() {
        assertTrue(JaTitles.same("ばくまん。", "バクマン"))
        assertTrue(JaTitles.same("ＫＩＮＧＤＯＭ", "kingdom"))
        assertTrue(JaTitles.same("キング ダム", "キングダム"))
    }

    @Test
    fun trailing_bracket_note_is_ignored_but_other_titles_are_not_matched() {
        assertTrue(JaTitles.same("キングダム（原作）", "キングダム"))
        assertFalse(JaTitles.same("キングダム", "キングダムハーツ"))
        assertFalse(JaTitles.same("", "キングダム"))
    }

    @Test
    fun chapter_numbers_are_read_from_the_usual_japanese_and_latin_forms() {
        assertEquals(35.0, JaTitles.chapterNumber("[第35話] カテナチオ")!!, 0.0)
        assertEquals(12.5, JaTitles.chapterNumber("第12.5話")!!, 0.0)
        assertEquals(7.0, JaTitles.chapterNumber("Chapter 7")!!, 0.0)
        assertEquals(12.0, JaTitles.chapterNumber("#12 title")!!, 0.0)
        assertEquals(160.0, JaTitles.chapterNumber("[160話]タイトル")!!, 0.0)
        assertNull(JaTitles.chapterNumber("[番外編20]タイトル"))
        assertNull(JaTitles.chapterNumber("番外編"))
    }

    @Test
    fun author_check_passes_when_unknown_and_fails_on_a_different_name() {
        assertTrue(JaTitles.authorMatches(null, listOf("原泰久")))
        assertTrue(JaTitles.authorMatches("原作／あーもんど 漫画／かしい葵", emptyList()))
        assertTrue(JaTitles.authorMatches("原泰久", listOf("原泰久")))
        assertTrue(JaTitles.authorMatches("原作／原泰久", listOf("原 泰久")))
        assertFalse(JaTitles.authorMatches("山田太郎", listOf("原泰久")))
    }
}

class PageDecoderTest {

    @Test
    fun xor_with_a_repeating_key_is_its_own_inverse() {
        val data = ByteArray(37) { (it * 7).toByte() }
        val encoded = PageDecoder.xor(data, "483a68d5ad41d0e7")
        assertFalse(encoded.contentEquals(data))
        assertTrue(PageDecoder.xor(encoded, "483a68d5ad41d0e7").contentEquals(data))
    }

    @Test
    fun a_broken_or_empty_key_leaves_the_data_untouched() {
        val data = byteArrayOf(1, 2, 3)
        assertTrue(PageDecoder.xor(data, "").contentEquals(data))
        assertTrue(PageDecoder.xor(data, "zz").contentEquals(data))
        assertTrue(PageDecoder.xor(data, "abc").contentEquals(data))
    }

    @Test
    fun giga_blocks_are_a_multiple_of_eight_pixels_and_cover_a_four_by_four_grid() {
        val cells = PageDecoder.gigaCells(800, 1150)
        assertEquals(16, cells.size)
        assertTrue(cells.all { it.width == 200 && it.height == 280 }) // 800/32*8, 1150/32*8
        assertTrue(cells.all { it.width % 8 == 0 && it.height % 8 == 0 })
    }

    @Test
    fun giga_descramble_restores_a_page_that_was_scrambled_by_the_inverse_move() {
        val width = 822
        val height = 1200
        val original = IntArray(width * height) { it }
        // Сайт перемешивает обратной перестановкой: блок из (dst) уходит в (src). Края остаются.
        val scrambled = original.copyOf()
        for (c in PageDecoder.gigaCells(width, height)) {
            for (y in 0 until c.height) for (x in 0 until c.width) {
                scrambled[(c.srcY + y) * width + (c.srcX + x)] = original[(c.dstY + y) * width + (c.dstX + x)]
            }
        }
        assertFalse(scrambled.contentEquals(original))

        val restored = scrambled.copyOf()
        for (c in PageDecoder.gigaCells(width, height)) {
            for (y in 0 until c.height) for (x in 0 until c.width) {
                restored[(c.dstY + y) * width + (c.dstX + x)] = scrambled[(c.srcY + y) * width + (c.srcX + x)]
            }
        }
        assertTrue(restored.contentEquals(original))
    }

    @Test
    fun a_tiny_image_has_nothing_to_rearrange() {
        assertTrue(PageDecoder.gigaCells(20, 20).isEmpty())
    }
}

class GigaViewerParserTest {

    private val base = "https://example.test"

    @Test
    fun old_design_search_results_give_title_author_and_the_first_episode() {
        val html = """
            <ul class="series-list"><li data-title="テスト作品">
              <div class="thmb-container"><a href="https://example.test/episode/111"><img src="https://cdn.example.test/t.jpg"></a></div>
              <div class="title-box"><p class="series-title">テスト作品</p><p class="author">山田太郎</p>
              <a href="https://example.test/episode/111" class="main-link">1話を読む</a></div></li></ul>
        """.trimIndent()
        val hits = GigaViewerParser.parseSearch(html, base)
        assertEquals(1, hits.size)
        assertEquals("111", hits[0].episodeId)
        assertEquals("テスト作品", hits[0].title)
        assertEquals("山田太郎", hits[0].author)
    }

    @Test
    fun new_design_search_results_with_module_class_names_are_found_too() {
        val html = """
            <ul class="SearchResult_list__a"><li><a href="https://example.test/episode/333">
              <h3 class="SearchResultItem_series_title__xyz">別の作品</h3>
              <p class="SearchResultItem_author__abc">佐藤花子</p></a></li></ul>
        """.trimIndent()
        val hit = GigaViewerParser.parseSearch(html, base).single()
        assertEquals("333", hit.episodeId)
        assertEquals("別の作品", hit.title)
        assertEquals("佐藤花子", hit.author)
    }

    @Test
    fun rss_lists_only_what_the_feed_has_with_numbers_and_dates() {
        val xml = """
            <?xml version="1.0"?><rss version="2.0"><channel>
            <item><title>[第35話] カテナチオ</title><link>https://example.test/episode/9001</link>
              <pubDate>Fri, 02 Oct 2026 03:00:00 +0000</pubDate></item>
            <item><title>[第34話] 前の話</title><link>https://example.test/episode/9000</link>
              <pubDate>Fri, 25 Sep 2026 03:00:00 +0000</pubDate></item>
            <item><title>おまけ</title><link>https://example.test/episode/8999</link></item>
            </channel></rss>
        """.trimIndent()
        val entries = GigaViewerParser.parseRss(xml)
        assertEquals(listOf("9001", "9000", "8999"), entries.map { it.episodeId })
        assertEquals(35.0, entries[0].number!!, 0.0)
        assertNull(entries[2].number)
        assertTrue(entries[0].publishedAtMillis > entries[1].publishedAtMillis)
    }

    private fun episodeHtml(json: String) =
        "<html><body><div data-aggregate-id=\"777\"></div>" +
            "<script id='episode-json' type='text/json' data-value='${json.replace("\"", "&quot;")}'></script></body></html>"

    @Test
    fun scrambled_episode_is_flagged_and_only_main_pages_are_taken() {
        val html = episodeHtml(
            """{"readableProduct":{"isPublic":true,"pageStructure":{"choJuGiga":"baku","pages":[
            {"type":"other"},{"type":"main","src":"https://cdn.example.test/p1","width":800,"height":1150},
            {"type":"main","src":"https://cdn.example.test/p2","width":800,"height":1150},{"type":"other"}]}}}""",
        )
        val episode = GigaViewerParser.parseEpisode(html)!!
        assertTrue(episode.isPublic)
        assertTrue(episode.scrambled)
        assertEquals(listOf("https://cdn.example.test/p1", "https://cdn.example.test/p2"), episode.pages)
    }

    @Test
    fun plain_episode_is_not_flagged() {
        val html = episodeHtml(
            """{"readableProduct":{"isPublic":true,"pageStructure":{"choJuGiga":"usagi","pages":[
            {"type":"main","src":"https://cdn.example.test/p1"}]}}}""",
        )
        assertFalse(GigaViewerParser.parseEpisode(html)!!.scrambled)
    }

    @Test
    fun a_locked_episode_has_no_pages() {
        val html = episodeHtml("""{"readableProduct":{"isPublic":false,"pageStructure":null}}""")
        val episode = GigaViewerParser.parseEpisode(html)!!
        assertFalse(episode.isPublic)
        assertTrue(episode.pages.isEmpty())
    }

    @Test
    fun aggregate_id_comes_from_the_page_or_from_the_embedded_json() {
        assertEquals("777", GigaViewerParser.parseAggregateId(episodeHtml("""{"readableProduct":{}}""")))
        val onlyJson = "<script id='episode-json' data-value='{&quot;readableProduct&quot;:{&quot;series&quot;:{&quot;id&quot;:&quot;55&quot;}}}'></script>"
        assertEquals("55", GigaViewerParser.parseAggregateId(onlyJson))
    }

    @Test
    fun a_page_without_the_json_is_not_an_episode() {
        assertNull(GigaViewerParser.parseEpisode("<html></html>"))
    }
}

class ComicWalkerParserTest {

    private val source = MangaSourceId("ja-comicwalker")

    @Test
    fun search_gives_code_title_and_joined_authors() {
        val json = Json.parseToJsonElement(
            """{"pagination":{"total":1},"result":[{"code":"KC_000001_S","title":"テスト","thumbnail":"https://c.example/t.jpg",
            "authors":[{"name":"山田"},{"name":"佐藤"}]}]}""",
        )
        val item = ComicWalkerParser.parseSearch(json, source).single()
        assertEquals("KC_000001_S", item.key)
        assertEquals("テスト", item.title)
        assertEquals("山田 佐藤", item.author)
    }

    @Test
    fun episodes_are_merged_without_duplicates_and_inactive_ones_are_dropped() {
        val json = Json.parseToJsonElement(
            """{"firstEpisodes":{"result":[{"id":"a","title":"第1話","subTitle":"始まり","isActive":true,"updateDate":"2026-01-01T00:00:00Z",
              "type":"normal","internal":{"episodeNo":1,"pageCount":20}}]},
            "latestEpisodes":{"result":[
              {"id":"a","title":"第1話","isActive":true,"type":"normal","internal":{"episodeNo":1}},
              {"id":"b","title":"第2話","isActive":true,"type":"normal","internal":{"episodeNo":2}},
              {"id":"c","title":"特別編","isActive":true,"type":"extra","internal":{"episodeNo":3}},
              {"id":"d","title":"第3話","isActive":false,"type":"normal","internal":{"episodeNo":3}}]}}""",
        )
        val episodes = ComicWalkerParser.parseEpisodes(json)
        assertEquals(listOf("a", "b", "c"), episodes.map { it.id })
        assertEquals("第1話 始まり", episodes[0].title)
        assertEquals(1.0, episodes[0].number!!, 0.0)
        assertEquals(20, episodes[0].pageCount)
        assertEquals(2.0, episodes[1].number!!, 0.0)
        assertNull("an extra without a number in the title must not borrow the episode counter", episodes[2].number)
    }

    @Test
    fun viewer_pages_carry_their_xor_key() {
        val json = Json.parseToJsonElement(
            """{"manuscripts":[{"drmMode":"xor","drmHash":"9679b58ed7dc4976","drmImageUrl":"https://c.example/1.webp"},
            {"drmMode":"xor","drmHash":"483a68d5ad41d0e7","drmImageUrl":"https://c.example/2.webp"}]}""",
        )
        val pages = ComicWalkerParser.parseViewer(json)
        assertEquals(2, pages.size)
        assertEquals("9679b58ed7dc4976", pages[0].xorKey)
        assertEquals("https://c.example/2.webp", pages[1].url)
    }

    @Test
    fun an_unknown_encryption_mode_gives_no_pages_instead_of_garbage() {
        val json = Json.parseToJsonElement(
            """{"manuscripts":[{"drmMode":"xor","drmHash":"00","drmImageUrl":"https://c.example/1.webp"},
            {"drmMode":"aes","drmHash":"00","drmImageUrl":"https://c.example/2.webp"}]}""",
        )
        assertTrue(ComicWalkerParser.parseViewer(json).isEmpty())
    }

    @Test
    fun unencrypted_pages_have_no_key() {
        val json = Json.parseToJsonElement("""{"manuscripts":[{"drmImageUrl":"https://c.example/1.webp"}]}""")
        assertNull(ComicWalkerParser.parseViewer(json).single().xorKey)
    }
}

class GanganParserTest {

    private fun page(props: String) =
        "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":$props}}</script></html>"

    @Test
    fun catalog_is_flattened_from_sections_without_duplicates() {
        val html = page(
            """{"data":{"sections":[{"titleLinks":[
            {"titleId":1,"name":"作品A","author":"山田","imageUrl":"/secure/a.webp"},
            {"titleId":2,"name":"作品B","imageUrl":"/secure/b.webp"}]},
            {"titleLinks":[{"titleId":1,"name":"作品A"}]}]}}""",
        )
        val items = GanganParser.parseCatalog(html, MangaSourceId("ja-gangan"))
        assertEquals(listOf("1", "2"), items.map { it.key })
        assertEquals("山田", items[0].author)
        assertTrue(items[0].coverUrl!!.startsWith("https://www.ganganonline.com/"))
    }

    @Test
    fun app_only_and_upcoming_chapters_are_not_offered() {
        val data = GanganParser.nextData(
            page(
                """{"data":{"default":{"chapters":[
                {"id":3,"status":3,"mainText":"次回更新","subText":"第49鐘-2","appLaunchUrl":"https://x"},
                {"id":4,"mainText":"第49鐘-1","subText":"ラキア","publishingPeriod":"2026.10.05〜2026.10.18"},
                {"id":5,"mainText":"第48鐘","subText":"ラキア","appLaunchUrl":"https://x"},
                {"id":6,"mainText":"第0鐘-2","subText":"カンパネラ"}]}}}""",
            ),
        )!!
        val chapters = GanganParser.parseChapters(data)
        assertEquals(listOf("4", "6"), chapters.map { it.id })
        assertEquals(49.1, chapters[0].number!!, 0.0001)
        assertEquals(0.2, chapters[1].number!!, 0.0001)
    }

    @Test
    fun chapter_numbers_with_and_without_a_part_suffix() {
        assertEquals(48.0, GanganParser.chapterNumber("第48鐘")!!, 0.0)
        assertEquals(12.3, GanganParser.chapterNumber("第12話-3")!!, 0.0001)
    }

    @Test
    fun pages_skip_banner_inserts() {
        val data = GanganParser.nextData(
            page(
                """{"data":{"pages":[{"image":{"imageUrl":"/p1.webp"}},{"image":{"imageUrl":"/p2.webp"}},
                {"linkImage":{"imageUrl":"/ad.webp","url":"https://x"}}]}}""",
            ),
        )!!
        assertEquals(listOf("/p1.webp", "/p2.webp"), GanganParser.parsePages(data))
    }

    @Test
    fun a_page_without_next_data_gives_nothing() {
        assertNull(GanganParser.nextData("<html></html>"))
    }
}
