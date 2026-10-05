package com.example.myapplication.manga.ja

import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSearchPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.VetroMangaSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JaTailResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Источник из памяти: серии и главы заданы в тесте, число поисков считается. */
    private class FakeSource(
        key: String,
        private val series: Map<String, Pair<String, String?>>, // ключ -> (название, автор)
        private val chapters: Map<String, List<MangaChapter>>,
        /** Сайт числит главы бесплатными, но без входа страниц не отдаёт. */
        private val locked: Boolean = false,
    ) : VetroMangaSource {
        override val id = MangaSourceId(key)
        override val name = key
        override val language = "ja"
        var searches = 0

        override suspend fun search(query: String, page: Int): MangaSearchPage {
            searches++
            return MangaSearchPage(series.map { (k, v) -> MangaItem(id, k, v.first, author = v.second) })
        }

        override suspend fun details(manga: MangaItem) = MangaDetails(manga)

        override suspend fun chapters(manga: MangaItem): List<MangaChapter> = chapters[manga.key].orEmpty()

        override suspend fun pages(chapter: MangaChapter): List<MangaPage> =
            if (locked) emptyList() else listOf(MangaPage(0, "https://example.test/p0.jpg"))
    }

    private class FakeNatives(private val entry: NativeEntry?) : NativeTitles {
        var calls = 0
        override suspend fun resolve(animeId: String, queries: List<String>): NativeEntry? {
            calls++
            return entry
        }
    }

    private fun ch(source: String, key: String, number: Double?, published: Long = 0, paid: Boolean = false, lang: String = "ja") =
        MangaChapter(MangaSourceId(source), "s", key, number = number, language = lang, publishedAt = published, paid = paid)

    private fun <V> store(name: String, serializer: kotlinx.serialization.KSerializer<V>) =
        JsonMapFileStore(tmp.newFile(name), serializer, name, logError = { _, _ -> })

    private fun resolver(sources: List<FakeSource>, natives: NativeTitles, english: EnglishTail? = null): JaTailResolver =
        JaTailResolver(
            sources,
            natives,
            store("matches.json", JaMatch.serializer()),
            store("health.json", JaHealth.serializer()),
            english = english,
        )

    /** Английский хвост из памяти: отдаёт заданные главы новее запрошенной. */
    private class FakeEnglish(private val chapters: List<MangaChapter>) : EnglishTail {
        var calls = 0
        override suspend fun chaptersAfter(
            animeId: String,
            queries: List<String>,
            lastKnown: Double,
            allowSearch: Boolean,
        ): List<MangaChapter> {
            calls++
            return chapters.filter { it.number!! > lastKnown }
        }
    }

    private val kingdom = NativeEntry(titles = listOf("キングダム"), authors = listOf("原泰久"))

    @Test
    fun finds_the_series_by_exact_title_and_returns_only_chapters_after_the_last_known_one() = runBlocking {
        val source = FakeSource(
            "a",
            series = mapOf("k1" to ("キングダム" to "原泰久"), "k2" to ("キングダムハーツ" to "野村")),
            chapters = mapOf(
                "k1" to listOf(
                    ch("a", "c1", 878.0), ch("a", "c2", 879.0), ch("a", "c3", 880.0),
                    ch("a", "c4", 881.0, paid = true), ch("a", "c5", null),
                ),
            ),
        )
        val tail = resolver(listOf(source), FakeNatives(kingdom))
            .resolve(TailRequest("anime1", listOf("Kingdom"), lastKnownNumber = 878.0))
        assertEquals(listOf(879.0, 880.0), tail.map { it.number })
    }

    @Test
    fun a_series_with_the_same_name_but_a_different_author_is_not_taken() = runBlocking {
        val source = FakeSource("a", mapOf("k1" to ("キングダム" to "山田太郎")), mapOf("k1" to listOf(ch("a", "c1", 900.0))))
        assertTrue(resolver(listOf(source), FakeNatives(kingdom)).resolve(TailRequest("anime1", listOf("Kingdom"), 1.0)).isEmpty())
    }

    @Test
    fun a_miss_is_remembered_and_the_sites_are_not_searched_again() = runBlocking {
        val source = FakeSource("a", mapOf("k1" to ("別の作品" to null)), emptyMap())
        val natives = FakeNatives(kingdom)
        val resolver = resolver(listOf(source), natives)
        repeat(3) { resolver.resolve(TailRequest("anime1", listOf("Kingdom"), 1.0)) }
        assertEquals(1, source.searches)
        assertEquals(1, natives.calls)
    }

    @Test
    fun without_a_japanese_title_nothing_is_searched() = runBlocking {
        val source = FakeSource("a", mapOf("k1" to ("キングダム" to null)), mapOf("k1" to listOf(ch("a", "c1", 5.0))))
        assertTrue(resolver(listOf(source), FakeNatives(null)).resolve(TailRequest("anime1", listOf("Kingdom"), 1.0)).isEmpty())
        assertEquals(0, source.searches)
    }

    @Test
    fun the_more_reliable_source_wins_when_two_sites_have_the_series() = runBlocking {
        val slow = FakeSource("slow", mapOf("s" to ("キングダム" to null)), mapOf("s" to listOf(ch("slow", "x", 10.0))))
        val fast = FakeSource("fast", mapOf("f" to ("キングダム" to null)), mapOf("f" to listOf(ch("fast", "y", 10.0))))
        val health = store("health.json", JaHealth.serializer())
        health.update {
            mapOf(
                "slow" to JaHealth(ok = 1, fail = 9, latencyMs = 5000),
                "fast" to JaHealth(ok = 20, fail = 0, latencyMs = 200),
            )
        }
        val resolver = JaTailResolver(listOf(slow, fast), FakeNatives(kingdom), store("matches.json", JaMatch.serializer()), health)
        val tail = resolver.resolve(TailRequest("anime1", listOf("Kingdom"), 1.0))
        assertEquals("fast", tail.single().sourceId.value)
    }

    @Test
    fun the_found_series_is_reused_by_the_background_path_without_searching() = runBlocking {
        val source = FakeSource("a", mapOf("k1" to ("キングダム" to null)), mapOf("k1" to listOf(ch("a", "c", 900.0))))
        val resolver = resolver(listOf(source), FakeNatives(kingdom))
        resolver.resolve(TailRequest("anime1", listOf("Kingdom"), 1.0))
        val searches = source.searches
        val again = resolver.cached("anime1", 1.0)
        assertEquals(1, again.size)
        assertEquals(searches, source.searches)
        assertTrue(resolver.cached("other-anime", 1.0).isEmpty())
    }

    @Test
    fun with_tail_appends_japanese_chapters_after_the_users_last_one() = runBlocking {
        val source = FakeSource("a", mapOf("k1" to ("キングダム" to null)), mapOf("k1" to listOf(ch("a", "j1", 879.0), ch("a", "j0", 5.0))))
        val resolver = resolver(listOf(source), FakeNatives(kingdom))
        val base = listOf(ch("remanga", "r877", 877.0, lang = "ru"), ch("remanga", "r878", 878.0, lang = "ru"))
        val merged = resolver.withTail(base, "anime1", "ru", listOf("Kingdom"), allowSearch = true)
        assertEquals(listOf(877.0, 878.0, 879.0), merged.map { it.number })
        assertEquals("ja", merged.last().language)
    }

    @Test
    fun with_tail_needs_a_language_and_an_anchor_chapter() = runBlocking {
        val source = FakeSource("a", mapOf("k1" to ("キングダム" to null)), mapOf("k1" to listOf(ch("a", "j1", 879.0))))
        val resolver = resolver(listOf(source), FakeNatives(kingdom))
        val ru = listOf(ch("remanga", "r1", 1.0, lang = "ru"))
        // Язык не выбран - якоря нет.
        assertEquals(ru, resolver.withTail(ru, "anime1", null, listOf("Kingdom"), allowSearch = true))
        // Выбран английский, а глав на нём нет - тоже.
        assertEquals(ru, resolver.withTail(ru, "anime1", "en", listOf("Kingdom"), allowSearch = true))
        assertEquals(0, source.searches)
    }

    @Test
    fun chapters_the_site_will_not_open_without_a_login_are_not_offered() = runBlocking {
        val gated = FakeSource("gated", mapOf("g" to ("キングダム" to null)), mapOf("g" to listOf(ch("gated", "x", 879.0), ch("gated", "y", 880.0))), locked = true)
        assertTrue(resolver(listOf(gated), FakeNatives(kingdom)).resolve(TailRequest("anime1", listOf("Kingdom"), 878.0)).isEmpty())
    }

    @Test
    fun a_gated_site_does_not_hide_the_chapters_of_an_open_one() = runBlocking {
        val gated = FakeSource("gated", mapOf("g" to ("キングダム" to null)), mapOf("g" to listOf(ch("gated", "x", 879.0))), locked = true)
        val open = FakeSource("open", mapOf("o" to ("キングダム" to null)), mapOf("o" to listOf(ch("open", "y", 879.0), ch("open", "z", 880.0))))
        val tail = resolver(listOf(gated, open), FakeNatives(kingdom)).resolve(TailRequest("anime1", listOf("Kingdom"), 878.0))
        assertEquals(listOf(879.0, 880.0), tail.map { it.number })
        assertTrue(tail.all { it.sourceId.value == "open" })
    }

    @Test
    fun one_series_on_two_open_sites_gives_the_union_of_their_chapters() = runBlocking {
        val first = FakeSource("first", mapOf("a" to ("キングダム" to null)), mapOf("a" to listOf(ch("first", "a1", 879.0))))
        val second = FakeSource("second", mapOf("b" to ("キングダム" to null)), mapOf("b" to listOf(ch("second", "b1", 879.0), ch("second", "b2", 880.0))))
        val tail = resolver(listOf(first, second), FakeNatives(kingdom)).resolve(TailRequest("anime1", listOf("Kingdom"), 878.0))
        assertEquals(listOf(879.0, 880.0), tail.map { it.number })
    }

    @Test
    fun english_chapters_extend_the_tail_when_the_original_has_nothing_free() = runBlocking {
        val gated = FakeSource("gated", mapOf("g" to ("キングダム" to null)), mapOf("g" to listOf(ch("gated", "x", 879.0))), locked = true)
        val english = FakeEnglish(listOf(ch("mangadex", "e879", 879.0, lang = "en"), ch("mangadex", "e880", 880.0, lang = "en")))
        val base = listOf(ch("remanga", "r878", 878.0, lang = "ru"))
        val merged = resolver(listOf(gated), FakeNatives(kingdom), english)
            .withTail(base, "anime1", "ru", listOf("Kingdom"), allowSearch = true)
        assertEquals(listOf(878.0, 879.0, 880.0), merged.map { it.number })
        assertEquals(listOf("ru", "en", "en"), merged.map { it.language })
    }

    @Test
    fun a_chapter_present_in_japanese_and_english_is_taken_in_japanese() = runBlocking {
        val open = FakeSource("open", mapOf("o" to ("キングダム" to null)), mapOf("o" to listOf(ch("open", "j879", 879.0))))
        val english = FakeEnglish(listOf(ch("mangadex", "e879", 879.0, lang = "en"), ch("mangadex", "e880", 880.0, lang = "en")))
        val base = listOf(ch("remanga", "r878", 878.0, lang = "ru"))
        val merged = resolver(listOf(open), FakeNatives(kingdom), english)
            .withTail(base, "anime1", "ru", listOf("Kingdom"), allowSearch = true)
        assertEquals(listOf("r878", "j879", "e880"), merged.map { it.key })
    }

    @Test
    fun english_is_not_asked_when_the_user_already_reads_english_or_the_source_has_english() = runBlocking {
        val english = FakeEnglish(listOf(ch("mangadex", "e9", 9.0, lang = "en")))
        val resolver = resolver(emptyList(), FakeNatives(kingdom), english)
        val readsEnglish = listOf(ch("mangadex", "e1", 1.0, lang = "en"))
        assertEquals(readsEnglish, resolver.withTail(readsEnglish, "anime1", "en", listOf("Kingdom"), allowSearch = true))
        val sourceHasEnglish = listOf(ch("mangadex", "r1", 1.0, lang = "ru"), ch("mangadex", "e5", 5.0, lang = "en"))
        assertEquals(sourceHasEnglish, resolver.withTail(sourceHasEnglish, "anime1", "ru", listOf("Kingdom"), allowSearch = true))
        assertEquals(0, english.calls)
    }

    @Test
    fun a_failing_english_chain_does_not_break_the_list() = runBlocking {
        val broken = object : EnglishTail {
            override suspend fun chaptersAfter(animeId: String, queries: List<String>, lastKnown: Double, allowSearch: Boolean): List<MangaChapter> =
                throw java.io.IOException("offline")
        }
        val base = listOf(ch("remanga", "r1", 1.0, lang = "ru"))
        assertEquals(base, resolver(emptyList(), FakeNatives(kingdom), broken).withTail(base, "anime1", "ru", listOf("Kingdom"), allowSearch = true))
    }

    @Test
    fun background_mode_never_starts_a_search() = runBlocking {
        val source = FakeSource("a", mapOf("k1" to ("キングダム" to null)), mapOf("k1" to listOf(ch("a", "j1", 879.0))))
        val resolver = resolver(listOf(source), FakeNatives(kingdom))
        val base = listOf(ch("remanga", "r1", 878.0, lang = "ru"))
        assertEquals(base, resolver.withTail(base, "anime1", "ru", listOf("Kingdom"), allowSearch = false))
        assertEquals(0, source.searches)
    }
}
