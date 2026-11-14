package com.example.myapplication.microml

import com.example.myapplication.microml.contribution.ContributionBatch
import com.example.myapplication.microml.contribution.ContributionSchema
import com.example.myapplication.microml.contribution.PairEntry
import com.example.myapplication.microml.contribution.TasteEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionBatchTest {
    private val install = "0123456789abcdef0123456789abcdef"
    private fun pair(label: Int = 1, features: List<Float> = List(ContributionSchema.FEATURE_COUNT) { 0.25f }) =
        PairEntry("identity-2026-11-10-stable", "picker_choice", label, listOf(0, 0), features)

    @Test fun `genre strings must be short names, not sentences, addresses or contacts`() {
        assertEquals("Slice of Life", ContributionSchema.genre("  Slice of Life "))
        assertEquals("Sci-Fi & Fantasy", ContributionSchema.genre("Sci-Fi & Fantasy"))
        assertEquals("Сёнен", ContributionSchema.genre("Сёнен"))
        assertEquals("アクション", ContributionSchema.genre("アクション"))
        for (bad in listOf("", "x", "a@b.co", "https://evil.example/a", "x".repeat(41), "Мой любимый тайтл: Death Note", "line\nbreak", "<b>", "Death Note (2006)")) {
            assertNull("must reject <$bad>", ContributionSchema.genre(bad))
        }
    }

    @Test fun `counter keys are lower-cased codes, free text is dropped`() {
        assertEquals("identity:rec_same_confident", ContributionSchema.counterKey("identity:rec_SAME_CONFIDENT"))
        assertNull(ContributionSchema.counterKey("my title is here"))
        assertNull(ContributionSchema.counterKey("identity:" + "a".repeat(60)))
    }

    @Test fun `a batch contains only the schema fields and no title-like text`() {
        val batch = ContributionBatch.build(
            install = install,
            packs = mapOf("identity" to "identity-2026-11-10-stable", "other" to "x"),
            taste = listOf(TasteEntry("ANIME", "experience-2026-11-10-stable", 40, List(11) { 0.5f })),
            pairs = listOf(pair()),
            genres = listOf("Slice of Life", "Death Note: my notes about it", "Slice of Life"),
            counters = mapOf("identity:search_same_top" to 12L, "Death Note" to 5L, "catalog:genre_unmapped" to 0L),
        )
        assertEquals(setOf("schema", "install", "packs", "taste", "pairs", "genres", "counters"), batch.keys)
        assertEquals(1, batch.getValue("schema").jsonPrimitive.int)
        assertEquals(setOf("identity"), batch.getValue("packs").jsonObject.keys)
        assertEquals(listOf("Slice of Life"), batch.getValue("genres").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("identity:search_same_top"), batch.getValue("counters").jsonObject.keys)
        val pairObject = batch.getValue("pairs").jsonArray.single().jsonObject
        assertEquals(setOf("features_version", "pack", "source", "label", "kinds", "features"), pairObject.keys)
        assertEquals(ContributionSchema.FEATURE_COUNT, pairObject.getValue("features").jsonArray.size)
        val text = ContributionBatch.compact(batch)
        assertFalse(text.contains("Death Note"))
        assertFalse(ContributionBatch.isEmpty(batch))
    }

    @Test fun `invalid pairs and weights are dropped, an empty batch is not sent`() {
        val batch = ContributionBatch.build(
            install = install, packs = emptyMap(),
            taste = listOf(TasteEntry("ANIME", "v1", 3, List(11) { 0.5f }), TasteEntry("ANIME", "v1", 40, List(10) { 0.5f }),
                TasteEntry("MANGA", "v1", 40, List(11) { 0.5f }), TasteEntry("ANIME", "v1", 40, List(11) { Float.NaN })),
            pairs = listOf(pair(features = List(41) { 0f }), pair(features = List(42) { Float.NaN }), pair(label = 2)),
            genres = emptyList(), counters = emptyMap(),
        )
        assertTrue((batch.getValue("taste") as JsonArray).isEmpty())
        assertTrue((batch.getValue("pairs") as JsonArray).isEmpty())
        assertTrue(ContributionBatch.isEmpty(batch))
    }

    @Test fun `the batch is capped at the server limits`() {
        val batch: JsonObject = ContributionBatch.build(
            install = install, packs = emptyMap(), taste = emptyList(),
            pairs = List(80) { pair() }, genres = List(90) { "Genre number ${it}x" },
            counters = (0 until 90).associate { "catalog:key_$it" to 1L },
        )
        assertEquals(ContributionSchema.MAX_PAIRS, batch.getValue("pairs").jsonArray.size)
        assertEquals(ContributionSchema.MAX_GENRES, batch.getValue("genres").jsonArray.size)
        assertEquals(ContributionSchema.MAX_COUNTERS, batch.getValue("counters").jsonObject.size)
    }

    private fun record(
        source: String = "knigavuhe",
        key: String = "/book/123-dune/",
        title: String = "Дюна",
        cover: String? = "https://example.org/c.jpg",
        year: Int? = 1965,
    ) = com.example.myapplication.microml.contribution.SourceRecord(source, key, title, listOf("Фрэнк Герберт"), listOf("Иван Иванов"), listOf("Sci-Fi"), year, cover, 61000)

    @Test fun `a source record keeps public book facts and drops anything the server would refuse`() {
        val ok = ContributionSchema.sourceRecord(record(source = " KnigaVuhe "))!!
        assertEquals("knigavuhe", ok.source)
        assertEquals(1965, ok.year)
        assertEquals("https://example.org/c.jpg", ok.cover)
        assertNull(ContributionSchema.sourceRecord(record(source = "Some Site!")))
        assertNull(ContributionSchema.sourceRecord(record(title = "  ")))
        assertNull(ContributionSchema.sourceRecord(record(title = "x".repeat(201))))
        assertNull(ContributionSchema.sourceRecord(record(key = "")))
        // непригодные необязательные поля отбрасываются, сама запись остаётся
        assertNull(ContributionSchema.sourceRecord(record(cover = "javascript:alert(1)"))!!.cover)
        assertNull(ContributionSchema.sourceRecord(record(cover = "https://ex ample.org/c.jpg"))!!.cover)
        assertNull(ContributionSchema.sourceRecord(record(year = 3000))!!.year)
    }

    @Test fun `the book batch has no install id, no query, no duplicates and respects the cap`() {
        val many = (1..150).map { record(key = "/book/$it/") } + record(key = "/book/1/")
        val batch = ContributionBatch.buildSources(many)
        assertEquals(setOf("schema", "records"), batch.keys)
        val records = batch["records"] as JsonArray
        assertEquals(ContributionSchema.MAX_RECORDS, records.size)
        assertEquals(ContributionSchema.MAX_RECORDS, records.map { it.jsonObject["key"]!!.jsonPrimitive.content }.toSet().size)
        assertEquals("Дюна", records.first().jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(ContributionBatch.isEmptySources(ContributionBatch.buildSources(emptyList())))
        assertTrue(ContributionBatch.isEmptySources(ContributionBatch.buildSources(listOf(record(source = "bad site")))))
        assertFalse(ContributionBatch.isEmptySources(batch))
    }
}
