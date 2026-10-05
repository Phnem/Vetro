package com.example.myapplication.manga.ja

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class YanMagaTest {

    /** Значения сняты с живого ヤンマガWeb: идентификатор главы, ключ запроса и таблицы, которые сервер ему ответил. */
    private class Vector(
        val cid: String,
        val key: String,
        val ctblEncrypted: String,
        val ptblEncrypted: String,
        val ctbl: List<String>,
        val ptbl: List<String>,
    )

    private val vector: Vector by lazy {
        val text = requireNotNull(javaClass.getResource("/speedbinb/vector.json")) { "vector.json is missing" }.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        fun list(name: String) = (root.getValue(name) as JsonArray).map { it.jsonPrimitive.content }
        Vector(
            cid = root.getValue("cid").jsonPrimitive.content,
            key = root.getValue("k").jsonPrimitive.content,
            ctblEncrypted = root.getValue("ctbl_enc").jsonPrimitive.content,
            ptblEncrypted = root.getValue("ptbl_enc").jsonPrimitive.content,
            ctbl = list("ctbl"),
            ptbl = list("ptbl"),
        )
    }

    // ---- ключ и таблицы ----

    @Test
    fun the_request_key_matches_values_computed_from_the_reader_algorithm() {
        assertEquals("AiBKCNDyEOF0GNH1IOJ6KTL9MgNOOuPJ", SpeedBinb.keyFor("06Z1100000000242FREE", "ABCDEFGHIJKLMNOP"))
        // Короткий cid повторяется до 16 символов.
        assertEquals("001G2z3E455B677F849FalbCcldKegfK", SpeedBinb.keyFor("abc", "0123456789abcdef"))
    }

    @Test
    fun a_random_key_has_the_expected_shape() {
        val key = SpeedBinb.makeKey("06Z1100000000242FREE", Random(7))
        assertEquals(32, key.length)
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun tables_from_the_server_decrypt_to_the_patterns() {
        assertEquals(vector.ctbl, SpeedBinb.decryptStrings(vector.cid, vector.key, vector.ctblEncrypted))
        assertEquals(vector.ptbl, SpeedBinb.decryptStrings(vector.cid, vector.key, vector.ptblEncrypted))
    }

    @Test
    fun a_wrong_key_does_not_decrypt() {
        assertNull(SpeedBinb.decryptStrings(vector.cid, "not-the-key", vector.ctblEncrypted))
        assertNull(SpeedBinb.decryptStrings("other-cid", vector.key, vector.ctblEncrypted))
    }

    // ---- раскладка ----

    @Test
    fun the_layout_of_a_real_page_matches_the_reference() {
        val layout = requireNotNull(SpeedBinb.layout("qR5hu3Pf.jpg", vector.ctbl, vector.ptbl, 1191, 1664))
        assertEquals(1127, layout.width)
        assertEquals(1600, layout.height)
        assertEquals(64, layout.cells.size)
        assertEquals(PageDecoder.Cell(4, 4, 422, 200, 141, 200), layout.cells.first())
        assertEquals(PageDecoder.Cell(897, 628, 986, 1000, 141, 200), layout.cells[30])
        assertEquals(PageDecoder.Cell(1046, 1460, 846, 1400, 141, 200), layout.cells.last())
    }

    @Test
    fun another_file_name_picks_another_pair_of_patterns() {
        val layout = requireNotNull(SpeedBinb.layout("mgxwHW5R.jpg", vector.ctbl, vector.ptbl, 1191, 1664))
        assertEquals(PageDecoder.Cell(4, 4, 986, 1400, 141, 200), layout.cells.first())
        assertEquals(PageDecoder.Cell(1047, 1460, 423, 200, 140, 200), layout.cells.last())
    }

    @Test
    fun the_pieces_tile_the_result_exactly_once() {
        for (name in listOf("qR5hu3Pf.jpg", "mgxwHW5R.jpg", "GzOl43nW.jpg")) {
            val layout = requireNotNull(SpeedBinb.layout(name, vector.ctbl, vector.ptbl, 1191, 1664))
            val covered = Array(layout.height) { BooleanArray(layout.width) }
            for (c in layout.cells) {
                assertTrue(c.srcX >= 0 && c.srcY >= 0 && c.srcX + c.width <= 1191 && c.srcY + c.height <= 1664)
                for (y in c.dstY until c.dstY + c.height) for (x in c.dstX until c.dstX + c.width) {
                    assertTrue("$name: overlap at $x,$y", !covered[y][x])
                    covered[y][x] = true
                }
            }
            assertTrue("$name: gaps left", covered.all { row -> row.all { it } })
        }
    }

    @Test
    fun small_pictures_and_empty_patterns_are_left_alone() {
        val small = requireNotNull(SpeedBinb.layout("x.jpg", vector.ctbl, vector.ptbl, 200, 200))
        assertEquals(listOf(PageDecoder.Cell(0, 0, 0, 0, 200, 200)), small.cells)
        val blank = List(8) { "" }
        val plain = requireNotNull(SpeedBinb.layout("x.jpg", blank, blank, 1191, 1664))
        assertEquals(1191, plain.width)
        assertEquals(1, plain.cells.size)
    }

    @Test
    fun unreadable_patterns_give_no_layout() {
        val garbage = List(8) { "garbage" }
        assertNull(SpeedBinb.layout("x.jpg", garbage, garbage, 1191, 1664))
        assertNull(SpeedBinb.layout("x.jpg", emptyList(), emptyList(), 1191, 1664))
    }

    // ---- разбор сайта ----

    private val searchHtml = """
        <html><body>
          <a href="/comics/authors">Authors</a><a href="/comics/series">Series</a>
          <a data-turbolinks="false" href="/comics/%E3%83%AA%E3%83%90">
            <div><img src="https://cdn.example/cover.jpg" alt="" /></div>
            <div><p class="t">リバース トー横キングダム</p><div><p class="a">カレーとネコ　ぐび</p></div></div>
          </a>
          <a href="/comics/%E3%83%AA%E3%83%90">duplicate without paragraphs</a>
        </body></html>
    """.trimIndent()

    @Test
    fun search_results_give_the_series_title_and_authors() {
        val hits = YanMagaParser.parseSearch(searchHtml)
        assertEquals(1, hits.size)
        assertEquals("%E3%83%AA%E3%83%90", hits[0].key)
        assertEquals("リバース トー横キングダム", hits[0].title)
        assertEquals("カレーとネコ　ぐび", hits[0].author)
        assertEquals("https://cdn.example/cover.jpg", hits[0].coverUrl)
        assertTrue(JaTitles.authorMatches(hits[0].author, listOf("ぐび")))
    }

    private fun episode(id: String, title: String, date: String, free: Boolean, gated: Boolean): String {
        val modal = if (gated) " js-modal" else ""
        val freeMark = if (free) """<span class="mod-episode-point--free">無料</span>""" else ""
        return """
            <li class="mod-episode-item$modal" data-episode-title="$title" data-is-free="false">
              <div class="mod-episode-public"><a class="mod-episode-link" href="/comics/S/$id">
                <time class="mod-episode-date">$date</time><p class="mod-episode-title">$title</p>
                <div class="mod-episode-price"><div class="mod-icon-point">
                  <span class="mod-episode-point--normal">70</span>$freeMark
                </div></div></a></div></li>
        """.trimIndent()
    }

    @Test
    fun only_free_chapters_without_a_registration_window_are_open() {
        val html = "<ul>" +
            episode("aaa", "第１話／始まり", "2025/03/17", free = true, gated = false) +
            episode("bbb", "第５話／登録が必要", "2025/04/14", free = true, gated = true) +
            episode("ccc", "第５８話／有料", "2026/09/28", free = false, gated = true) +
            "</ul>"
        val episodes = YanMagaParser.parseEpisodes(html)
        assertEquals(listOf("aaa", "bbb", "ccc"), episodes.map { it.id })
        assertEquals(listOf(true, false, false), episodes.map { it.openToAnyone })
        assertEquals(1.0, JaTitles.chapterNumber(episodes[0].title)!!, 0.0)
        assertEquals(58.0, JaTitles.chapterNumber(episodes[2].title)!!, 0.0)
        val expected = LocalDate.of(2025, 3, 17).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
        assertEquals(expected, episodes[0].publishedAtMillis)
    }

    @Test
    fun the_show_more_button_describes_the_rest_of_the_list() {
        val html = """<div class="more-button" data-limit="49" data-offset="5" data-path="/comics/S/episodes" data-sort="older">もっと見る</div>"""
        assertEquals(YanMagaParser.More("/comics/S/episodes", 5, 49, "older"), YanMagaParser.parseMore(html))
        assertNull(YanMagaParser.parseMore("<div></div>"))
    }

    @Test
    fun the_loaded_remainder_is_read_from_the_script() {
        val item = episode("ddd", "第２話／途中", "2025/03/24", free = true, gated = false)
        val escaped = Json.encodeToString(String.serializer(), item)
        val script = "var target = document.querySelector(\".mod-episode-list--close\");\n" +
            "    target.insertAdjacentHTML('beforeend', $escaped);"
        val episodes = YanMagaParser.parseEpisodesScript(script)
        assertEquals(listOf("ddd"), episodes.map { it.id })
        assertTrue(episodes.single().openToAnyone)
        assertTrue(YanMagaParser.parseEpisodesScript("nothing here").isEmpty())
    }

    @Test
    fun the_reader_address_gives_the_content_id() {
        val url = "https://yanmaga.jp/viewer/comics/%E3%83%AA/77c10c?cid=06Z1100000000242FREE"
        assertEquals("06Z1100000000242FREE", YanMagaParser.cidOf(url))
        // Редирект на страницу регистрации - не читалка.
        assertNull(YanMagaParser.cidOf("https://yanmaga.jp/members/login?cid=06Z1"))
        assertNull(YanMagaParser.cidOf("https://yanmaga.jp/viewer/comics/x"))
    }

    @Test
    fun the_reader_page_names_the_info_request() {
        val html = """<div id="content" data-ptbinb="/viewer/bibGetCntntInfo?random_identification=77c1&amp;type=comics"></div>"""
        assertEquals("/viewer/bibGetCntntInfo?random_identification=77c1&type=comics", YanMagaParser.infoPath(html))
        assertNull(YanMagaParser.infoPath("<div></div>"))
    }

    @Test
    fun the_chapter_info_gives_the_server_and_tables() {
        val ok = """{"result":1,"items":[{"ContentsServer":"https://sbc.example/books/X/1/2","ctbl":"c","ptbl":"p"}]}"""
        assertEquals(YanMagaParser.Info("https://sbc.example/books/X/1/2", "c", "p"), YanMagaParser.parseInfo(ok))
        assertNull(YanMagaParser.parseInfo("""{"result":-100,"items":[]}"""))
        assertNull(YanMagaParser.parseInfo("not json"))
    }

    @Test
    fun the_content_lists_the_pages_in_reading_order() {
        val ttx = """<html><body><t-img src="pages/a.jpg" a="0" orgwidth="1" id="P0000"><t-pb><t-img src="pages/b.jpg" a="0" id="P0001"></body></html>"""
        val json = Json.encodeToString(String.serializer(), ttx)
        assertEquals(listOf("pages/a.jpg", "pages/b.jpg"), YanMagaParser.parseContent("""{"result":1,"ttx":$json}"""))
        assertTrue(YanMagaParser.parseContent("""{"result":0}""").isEmpty())
        assertTrue(YanMagaParser.parseContent("""{"result":1,"ttx":"x"}""").isEmpty())
    }
}
