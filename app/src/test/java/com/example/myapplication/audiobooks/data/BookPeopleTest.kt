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
import com.example.myapplication.data.local.JsonMapFileStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BookPeopleTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: AudiobookRepository
    private lateinit var file: File

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AnimeDatabase.Schema.create(driver).value
        file = File.createTempFile("people", ".json").apply { delete() }
        val overrides = BookPeopleOverrides(JsonMapFileStore(file, serializer<List<String>>(), "test") { _, _ -> })
        repository = AudiobookRepository(AnimeDatabase(driver), overrides)
    }

    @After
    fun tearDown() {
        driver.close()
        file.delete()
    }

    @Test
    fun `names are split on commas and semicolons, blanks and repeats dropped`() {
        assertEquals(
            listOf("Деннис Тейлор", "Иван Иванов"),
            BookPeopleOverrides.parse("  Деннис   Тейлор, Иван Иванов ;; деннис тейлор\n"),
        )
        assertEquals(emptyList<String>(), BookPeopleOverrides.parse(" , ; "))
        assertEquals("А, Б", BookPeopleOverrides.format(listOf("А", "Б")))
    }

    @Test
    fun `swapped author and narrator move the book to the right shelf`() = runBlocking {
        // Сайт перепутал: «автор» — чтец, «чтец» — автор.
        val opened = repository.saveOpened(Fake, details(authors = listOf("Иван Иванов"), narrators = listOf("Деннис Тейлор")))
        repository.setInLibrary(opened.workId, true)
        assertEquals(listOf("Иван Иванов"), repository.library().first().single().authors)

        repository.setPeople(opened.workId, opened.narrationId, listOf("Деннис Тейлор"), listOf("Иван Иванов"))

        val shelf = repository.library().first().single()
        assertEquals(listOf("Деннис Тейлор"), shelf.authors)
        assertEquals(listOf("Иван Иванов"), shelf.narrators)
    }

    @Test
    fun `opening the book again does not bring back what the site wrote`() = runBlocking {
        val wrong = details(authors = listOf("Иван Иванов"), narrators = listOf("Деннис Тейлор"))
        val opened = repository.saveOpened(Fake, wrong)
        repository.setInLibrary(opened.workId, true)
        repository.setPeople(opened.workId, opened.narrationId, listOf("Деннис Тейлор"), listOf("Иван Иванов"))

        val again = repository.saveOpened(Fake, wrong)

        assertEquals(opened, again)
        assertEquals(listOf("Деннис Тейлор"), repository.library().first().single().authors)
        val patched = repository.withPeople(again, wrong.book)
        assertEquals(listOf("Деннис Тейлор"), patched.authors)
        assertEquals(listOf("Иван Иванов"), patched.narrators)
    }

    @Test
    fun `a book nobody edited keeps the names of its source`() = runBlocking {
        val book = details(authors = listOf("Лем"), narrators = listOf("Козий"))
        val opened = repository.saveOpened(Fake, book)
        assertEquals(book.book, repository.withPeople(opened, book.book))
    }

    @Test
    fun `an emptied narrator stays empty`() = runBlocking {
        val source = details(authors = listOf("А"), narrators = listOf("Б"))
        val opened = repository.saveOpened(Fake, source)
        repository.setPeople(opened.workId, opened.narrationId, listOf("А"), emptyList())
        assertEquals(emptyList<String>(), repository.withPeople(opened, source.book).narrators)
    }

    @Test
    fun `marking a book listened takes it out of continue listening and back`() = runBlocking {
        val opened = repository.saveOpened(Fake, details(authors = listOf("А"), narrators = listOf("Б")))
        repository.saveProgress(ProgressSnapshot(opened.workId, opened.narrationId, opened.variantId, 5_000, 0, 5_000, 3_600_000, 1f, false))
        assertEquals(1, repository.continueListening().first().size)

        repository.markFinished(opened, finished = true, totalMs = 3_600_000)
        assertTrue(repository.continueListening().first().isEmpty())
        assertEquals(3_600_000L, repository.progress(opened.narrationId)?.globalMs)

        repository.markFinished(opened, finished = false, totalMs = 3_600_000)
        assertEquals(1, repository.continueListening().first().size)
    }

    private fun details(authors: List<String>, narrators: List<String>) = SourceBookDetails(
        book = SourceBook(SourceBookRef(Fake.id, "all-these-worlds"), "Все эти миры", authors, narrators, null, 3600),
        description = null,
        chapterDurationsSec = listOf(1800, 1800),
    )

    private object Fake : AudiobookSource {
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
