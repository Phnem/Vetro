package com.example.myapplication.audiobooks.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.data.local.AnimeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudiobookRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: AudiobookRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AnimeDatabase.Schema.create(driver).value
        repository = AudiobookRepository(AnimeDatabase(driver)) { now }
    }

    @After
    fun tearDown() = driver.close()

    @Test
    fun `two narrations of one book share a work, same page keeps its ids`() = runBlocking {
        val gerasimov = repository.saveOpened(FakeSource, details("soliaris-gerasimov", listOf("Лем Станислав"), "Герасимов Вячеслав"))
        val kozii = repository.saveOpened(FakeSource, details("soliaris-kozii", listOf("Станислав Лем"), "Козий Николай"))
        val again = repository.saveOpened(FakeSource, details("soliaris-gerasimov", listOf("Лем Станислав"), "Герасимов Вячеслав"))

        assertEquals(gerasimov.workId, kozii.workId)
        assertNotEquals(gerasimov.narrationId, kozii.narrationId)
        assertEquals(gerasimov, again)
        assertEquals(VariantId("fake:soliaris-gerasimov"), gerasimov.variantId)
    }

    @Test
    fun `continue listening shows the newest unfinished book`() = runBlocking {
        val first = repository.saveOpened(FakeSource, details("a", listOf("Автор"), "Чтец А", title = "Первая"))
        val second = repository.saveOpened(FakeSource, details("b", listOf("Другой"), "Чтец Б", title = "Вторая"))
        repository.saveProgress(progress(first, globalMs = 5_000, finished = false))
        now = 2_000
        repository.saveProgress(progress(second, globalMs = 9_000, finished = false))

        val items = repository.continueListening().first()

        assertEquals(listOf("Вторая", "Первая"), items.map { it.title })
        assertEquals(listOf("Чтец Б"), items.first().narrators)
        assertEquals(9_000L, items.first().globalMs)

        now = 3_000
        repository.saveProgress(progress(second, globalMs = 10_000, finished = true))
        assertEquals(listOf("Первая"), repository.continueListening().first().map { it.title })
    }

    @Test
    fun `playback of an unknown local book creates minimal rows`() = runBlocking {
        val workId = com.example.myapplication.audiobooks.domain.model.WorkId.new()
        val narrationId = com.example.myapplication.audiobooks.domain.model.NarrationId.new()
        repository.ensureFromPlayback(PlaybackBookMeta(workId, narrationId, "Моя книга", null, null, null))
        repository.saveProgress(
            ProgressSnapshot(workId, narrationId, null, 1_000, 0, 1_000, null, 1f, finished = false),
        )
        assertTrue(repository.continueListening().first().any { it.title == "Моя книга" })
    }

    private fun details(key: String, authors: List<String>, narrator: String, title: String = "Солярис") =
        SourceBookDetails(
            book = SourceBook(SourceBookRef(FakeSource.id, key), title, authors, listOf(narrator), null, 3600),
            description = null,
            chapterDurationsSec = listOf(1800, 1800),
        )

    private fun progress(book: OpenedBook, globalMs: Long, finished: Boolean) = ProgressSnapshot(
        book.workId, book.narrationId, book.variantId, globalMs, 0, globalMs, 3_600_000, 1f, finished,
    )

    private object FakeSource : AudiobookSource {
        override val id = SourceId("fake")
        override val displayName = "Fake"
        override val languages = setOf(BookLanguage.RU)
        override val infrastructureGroup = "fake"
        override suspend fun search(query: String, page: Int) = SourceResult.Ok(emptyList<SourceBook>())
        override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = error("unused")
        override fun supports(variant: VariantId) = false
        override suspend fun refresh(variant: VariantId): MediaManifest = error("unused")
    }
}
