package com.example.myapplication.audiobooks.domain.enrichment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookAiIdentityTest {

    @Test
    fun `identity is parsed from json wrapped in prose`() {
        val parsed = BookAiIdentity.parseIdentity(
            """Sure! {"english_title": "All These Worlds", "english_authors": ["Dennis E. Taylor", "Деннис"]} Hope it helps.""",
        )
        assertEquals(BookIdentity("All These Worlds", listOf("Dennis E. Taylor")), parsed)
    }

    @Test
    fun `unknown or non latin answers are dropped`() {
        assertNull(BookAiIdentity.parseIdentity("""{"english_title": "", "english_authors": []}"""))
        assertNull(BookAiIdentity.parseIdentity("""{"english_title": "Unknown", "english_authors": ["null"]}"""))
        assertNull(BookAiIdentity.parseIdentity("""{"english_title": "Все эти миры", "english_authors": []}"""))
        assertNull(BookAiIdentity.parseIdentity("not json"))
        assertEquals(BookIdentity(null, listOf("Lem")), BookAiIdentity.parseIdentity("""{"english_title": "", "english_authors": ["Lem"]}"""))
    }

    @Test
    fun `pick is a one based number inside the list`() {
        assertEquals(2, BookAiIdentity.parsePick("""{"number": 3}""", 5))
        assertEquals(0, BookAiIdentity.parsePick("""{"number": "1"}""", 5))
        assertNull(BookAiIdentity.parsePick("""{"number": 0}""", 5))
        assertNull(BookAiIdentity.parsePick("""{"number": 9}""", 5))
        assertNull(BookAiIdentity.parsePick("""{"number": "none"}""", 5))
        assertNull(BookAiIdentity.parsePick("nope", 5))
    }

    @Test
    fun `without an ai key both tasks return nothing`() = runBlocking {
        val identity = BookAiIdentity(ask = null)
        assertNull(identity.identify("Все эти миры", listOf("Деннис Тейлор")))
        assertNull(identity.pickWork("Все эти миры", emptyList(), listOf("We Are Legion", "All These Worlds")))
    }

    @Test
    fun `an english book is not sent to the model`() = runBlocking {
        var calls = 0
        val identity = BookAiIdentity(ask = { _, _ -> calls++; Result.success("{}") })
        assertNull(identity.identify("All These Worlds", listOf("Dennis E. Taylor")))
        assertEquals(0, calls)
    }

    @Test
    fun `answers are cached, a failure is retried after a few minutes`() = runBlocking {
        var now = 0L
        var calls = 0
        var failing = true
        val identity = BookAiIdentity(
            ask = { _, _ ->
                calls++
                if (failing) Result.failure(IllegalStateException("429"))
                else Result.success("""{"english_title": "All These Worlds", "english_authors": ["Dennis E. Taylor"]}""")
            },
            nowMs = { now },
        )
        assertNull(identity.identify("Все эти миры", listOf("Деннис Тейлор")))
        assertNull(identity.identify("Все эти миры", listOf("Деннис Тейлор")))
        assertEquals(1, calls)

        failing = false
        now += BookAiIdentity.FAILED_TTL_MS + 1
        assertEquals("All These Worlds", identity.identify("Все эти миры", listOf("Деннис Тейлор"))?.englishTitle)
        identity.identify("Все эти миры", listOf("Деннис Тейлор"))
        assertEquals(2, calls)
    }

    @Test
    fun `the model picks a work by its number`() = runBlocking {
        val identity = BookAiIdentity(ask = { user, _ ->
            assertTrue(user.contains("3. All These Worlds"))
            Result.success("""{"number": 3}""")
        })
        val works = listOf("We Are Legion", "For We Are Many", "All These Worlds")
        assertEquals(2, identity.pickWork("Все эти миры", listOf("Деннис Тейлор"), works))
    }
}
