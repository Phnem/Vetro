package com.example.myapplication.audiobooks.catalog

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class BookNormTest {
    @Test
    fun matchesTheGoldenCasesSharedWithPythonAndTheWorker() {
        val golden = File("../book-dictionary/v2/tests/golden_norm.json")
        assumeTrue("golden file is not in this checkout", golden.exists())
        val cases = JSONArray(golden.readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("in"), c.getString("norm"), BookNorm.norm(c.getString("in")))
            if (c.has("translit")) assertEquals(c.getString("translit"), BookNorm.translit(BookNorm.norm(c.getString("in"))))
        }
    }

    @Test
    fun yotIsALetterButYoFolds() {
        assertEquals("война", BookNorm.norm("Война"))
        assertEquals("елка", BookNorm.norm("Ёлка"))
    }
}

class BookIndexPlannerTest {
    private fun manifest(version: Int, deltas: List<BookIndexManifest.DeltaRef>, schema: Int = 2, baseBytes: Long = 10_000) =
        BookIndexManifest(
            schema = schema, version = version,
            base = BookIndexManifest.BaseRef(version, "index/base/b.zst", baseBytes, "h", 30_000, "r"),
            deltas = deltas,
        )

    private fun delta(from: Int, to: Int, bytes: Long = 100) = BookIndexManifest.DeltaRef(from, to, "index/delta/$from-$to.zst", bytes, "h")

    @Test fun noLocalIndexTakesTheBase() = assertTrue(BookIndexPlanner.plan(null, manifest(3, emptyList())) is BookIndexPlan.FullBase)

    @Test fun sameVersionIsUpToDate() = assertEquals(BookIndexPlan.UpToDate, BookIndexPlanner.plan(3, manifest(3, emptyList())))

    @Test fun chainOfDeltasWhenCheap() {
        val plan = BookIndexPlanner.plan(1, manifest(3, listOf(delta(1, 2), delta(2, 3))))
        assertEquals(listOf(1 to 2, 2 to 3), (plan as BookIndexPlan.Deltas).steps.map { it.from to it.to })
    }

    @Test fun gapInTheChainFallsBackToTheBase() =
        assertTrue(BookIndexPlanner.plan(1, manifest(4, listOf(delta(1, 2), delta(3, 4)))) is BookIndexPlan.FullBase)

    @Test fun deltasCostingMoreThanHalfTheBaseAreSkipped() =
        assertTrue(BookIndexPlanner.plan(1, manifest(2, listOf(delta(1, 2, bytes = 6_000)))) is BookIndexPlan.FullBase)

    @Test fun localNewerThanServerResetsToTheBase() = assertTrue(BookIndexPlanner.plan(9, manifest(3, emptyList())) is BookIndexPlan.FullBase)

    @Test fun newerSchemaIsLeftAlone() = assertEquals(BookIndexPlan.Unsupported, BookIndexPlanner.plan(1, manifest(2, emptyList(), schema = 3)))
}

/** Настоящий FTS4 (JDBC SQLite): та же схема, что строит book-dictionary/v2/build/bookindex.py. */
class BookIndexSearchTest {
    private lateinit var conn: Connection
    private lateinit var search: BookIndexSearch

    @Before
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use {
            it.execute(
                "CREATE VIRTUAL TABLE books USING fts4(ru, en, ra, ea, alt, x, y, f, cv, notindexed=y, notindexed=f, notindexed=cv, " +
                    "matchinfo=fts3, tokenize=unicode61)",
            )
            it.execute("CREATE VIRTUAL TABLE books_terms USING fts4aux(books)")
        }
        fun add(id: Int, ru: String, en: String?, ra: String, ea: String?, alt: String, x: String, y: Int?, f: Int) =
            conn.prepareStatement("INSERT INTO books(docid, ru, en, ra, ea, alt, x, y, f, cv) VALUES (?,?,?,?,?,?,?,?,?,?)").use { p ->
                p.setInt(1, id); p.setString(2, ru); p.setString(3, en); p.setString(4, ra); p.setString(5, ea); p.setString(6, alt)
                p.setString(7, x); p.setObject(8, y); p.setInt(9, f); p.setInt(10, 0); p.executeUpdate()
            }
        add(771, "Фунты лиха в Париже и Лондоне", "Down and Out in Paris and London", "Джордж Оруэлл", "George Orwell", "", "", 1933, 5)
        add(5, "Чайка", "The Seagull", "Антон Чехов", "Anton Chekhov", "", "chayka", 1896, 5)
        add(7, "Чайка по имени Джонатан Ливингстон", "Jonathan Livingston Seagull", "Ричард Бах", "Richard Bach", "", "chayka", 1970, 1)
        add(6, "Ёлка", null, "Иван Иванов", null, "", "елка", null, 0)
        add(8, "1984", "Nineteen Eighty-Four", "Джордж Оруэлл", "George Orwell", "", "", 1949, 5)
        search = BookIndexSearch { sql, args ->
            conn.prepareStatement(sql).use { p ->
                args.forEachIndexed { i, a -> p.setString(i + 1, a) }
                p.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(List(rs.metaData.columnCount) { rs.getObject(it + 1) }) }
                }
            }
        }
    }

    @After fun tearDown() = conn.close()

    private fun ids(q: String) = search.search(q).map { it.id }

    @Test fun findsByTitleAuthorAndEnglishTitle() {
        assertEquals(listOf(771), ids("фунты лиха"))
        assertEquals(setOf(771, 8), ids("оруэлл").toSet())
        assertEquals(listOf(771), ids("down out paris"))
    }

    @Test fun foldsYoAndAcceptsLatinForRussianTitles() {
        assertEquals(listOf(6), ids("елка"))
        assertEquals(listOf(6), ids("Ёлка"))
        assertEquals(5, ids("chayka").first()) // «Чайка» целиком выше «Чайки по имени Джонатан…»
    }

    @Test fun exactTitleBeatsALongerOne() = assertEquals(listOf(5, 7), ids("чайка"))

    @Test fun fixesATypoUsingTheIndexVocabulary() {
        assertEquals(setOf(771, 8), ids("оруэл").toSet()) // недописанное слово — тоже префикс
        assertEquals(setOf(771, 8), ids("оруелл").toSet()) // пропущена «э»: найдено по исправленному слову
        assertEquals(5, ids("чайак").first()) // перестановка букв
    }

    @Test fun veryShortOrEmptyQueriesAnswerNothing() {
        assertTrue(ids("").isEmpty())
        assertTrue(ids("я").isEmpty())
    }

    @Test fun editDistanceWithTranspositions() {
        assertEquals(1, BookIndexSearch.distance("чайак", "чайка", 2))
        assertEquals(3, BookIndexSearch.distance("abcdef", "uvwxyz", 2)) // отсечка: больше max → max + 1
    }
}
