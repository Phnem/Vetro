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

class EnglishTailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** MangaDex из памяти: каталог знает одну мангу по номеру AniList, глав у неё заданный набор. */
    private class FakeDex(
        private val knownAniListId: Int?,
        private val chapters: List<MangaChapter>,
    ) : VetroMangaSource, AniListLookup {
        override val id = MangaSourceId("mangadex")
        override val name = "MangaDex"
        override val language = "multi"
        var lookups = 0
        var feeds = 0

        override suspend fun findByAniList(aniListId: Int, titles: List<String>): MangaItem? {
            lookups++
            return if (aniListId == knownAniListId) MangaItem(id, "dex-key", "Kingdom") else null
        }

        override suspend fun search(query: String, page: Int) = MangaSearchPage(emptyList())
        override suspend fun details(manga: MangaItem) = MangaDetails(manga)
        override suspend fun chapters(manga: MangaItem): List<MangaChapter> {
            feeds++
            return chapters
        }

        override suspend fun pages(chapter: MangaChapter): List<MangaPage> = emptyList()
    }

    private class FakeNatives(private val entry: NativeEntry?) : NativeTitles {
        var calls = 0
        override suspend fun resolve(animeId: String, queries: List<String>): NativeEntry? {
            calls++
            return entry
        }
    }

    private fun ch(key: String, number: Double?, lang: String = "en", paid: Boolean = false, published: Long = 0) =
        MangaChapter(MangaSourceId("mangadex"), "dex-key", key, number = number, language = lang, publishedAt = published, paid = paid)

    private fun tail(dex: FakeDex, natives: NativeTitles, clock: () -> Long = { 1_000L }) = MangaDexEnglishTail(
        catalog = dex,
        source = dex,
        natives = natives,
        matches = JsonMapFileStore(tmp.newFile(), EnMatch.serializer(), "en", logError = { _, _ -> }),
        clock = clock,
    )

    private val withId = NativeEntry(titles = listOf("キングダム"), aniListId = 30001)

    @Test
    fun returns_only_english_chapters_newer_than_the_last_known_one() = runBlocking {
        val dex = FakeDex(
            30001,
            listOf(
                ch("ru878", 878.0, lang = "ru"),
                ch("en878", 878.0),
                ch("en879", 879.0),
                ch("en880", 880.0),
                ch("paid", 881.0, paid = true),
                ch("none", null),
            ),
        )
        val result = tail(dex, FakeNatives(withId)).chaptersAfter("a1", listOf("Kingdom"), 878.0, allowSearch = true)
        assertEquals(listOf(879.0, 880.0), result.map { it.number })
        assertTrue(result.all { it.language == "en" })
    }

    @Test
    fun the_freshest_upload_of_a_repeated_chapter_wins() = runBlocking {
        val dex = FakeDex(30001, listOf(ch("old", 5.0, published = 10), ch("new", 5.0, published = 20)))
        val result = tail(dex, FakeNatives(withId)).chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = true)
        assertEquals(listOf("new"), result.map { it.key })
    }

    @Test
    fun a_manga_without_the_matching_anilist_link_is_not_taken() = runBlocking {
        val dex = FakeDex(knownAniListId = 999, chapters = listOf(ch("en9", 9.0)))
        assertTrue(tail(dex, FakeNatives(withId)).chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = true).isEmpty())
        assertEquals(0, dex.feeds)
    }

    @Test
    fun a_miss_is_remembered_and_not_searched_again() = runBlocking {
        val dex = FakeDex(knownAniListId = 999, chapters = emptyList())
        val tail = tail(dex, FakeNatives(withId))
        repeat(3) { tail.chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = true) }
        assertEquals(1, dex.lookups)
    }

    @Test
    fun without_an_anilist_number_nothing_is_looked_up() = runBlocking {
        val dex = FakeDex(30001, listOf(ch("en9", 9.0)))
        val result = tail(dex, FakeNatives(NativeEntry(titles = listOf("キングダム")))).chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = true)
        assertTrue(result.isEmpty())
        assertEquals(0, dex.lookups)
    }

    @Test
    fun background_mode_uses_only_a_known_match() = runBlocking {
        val dex = FakeDex(30001, listOf(ch("en9", 9.0)))
        val natives = FakeNatives(withId)
        val tail = tail(dex, natives)
        assertTrue(tail.chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = false).isEmpty())
        assertEquals(0, natives.calls)
        assertEquals(1, tail.chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = true).size)
        assertEquals(1, tail.chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = false).size)
    }

    @Test
    fun the_feed_is_fetched_once_within_the_memo_window() = runBlocking {
        val dex = FakeDex(30001, listOf(ch("en9", 9.0)))
        val tail = tail(dex, FakeNatives(withId))
        repeat(3) { tail.chaptersAfter("a1", listOf("Kingdom"), 1.0, allowSearch = true) }
        assertEquals(1, dex.feeds)
    }
}
