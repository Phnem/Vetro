package com.example.myapplication.microml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Kotlin-рантайм MicroML против эталонных векторов из tools/microml/export/pack.py. Без собранных пакетов тесты пропускаются. */
class MicroMlParityTest {
    private val root = File(System.getProperty("microml.fixtureRoot") ?: "../tools/microml/.cache/releases/current")
    private val source = MicroAssetSource { path -> File(File(root, "android-assets"), path).inputStream() }
    private val golden: JsonObject by lazy { Json.parseToJsonElement(File(root, "golden.json").readText()).jsonObject }

    private fun ready() = assumeTrue("Build MicroML packs first (tools/microml/export/pack.py)", File(root, "golden.json").isFile)
    private fun JsonElement.text() = jsonPrimitive.content
    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }
    private fun JsonElement?.intOrNull(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.int

    @Test fun `text spec matches Python`() {
        ready()
        val core = MicroPacks.loadCore(source)
        assertEquals(golden.getValue("encoder_sha256").text(), core.encoderSha256)
        for (row in golden.getValue("text").jsonArray.map { it.jsonObject }) {
            val text = row.getValue("text").text()
            val norm = MicroText.normalize(text)
            assertEquals("normalize <$text>", row.getValue("normalize").text(), norm)
            assertEquals("latin <$text>", row.getValue("latin").text(), MicroText.latin(norm))
            assertEquals("skeleton <$text>", row.getValue("skeleton").text(), MicroText.skeleton(norm))
            assertEquals("script <$text>", row.getValue("script").jsonPrimitive.int, MicroText.script(norm))
            assertEquals("buckets <$text>", row.getValue("buckets").jsonArray.map { it.jsonPrimitive.int }, MicroText.bucketIds(norm, core.encoder.buckets).toList())
            val markers = MicroText.markers(text)
            assertEquals("sequence <$text>", row.getValue("sequence").jsonArray.map { it.jsonPrimitive.int }, markers.sequence.sorted())
            assertEquals("variants <$text>", row.getValue("variants").strings(), markers.variants.sorted())
            assertEquals("years <$text>", row.getValue("years").jsonArray.map { it.jsonPrimitive.int }, markers.years.sorted())
            val vector = core.encoder.embed(norm)
            row.getValue("embedding").jsonArray.forEachIndexed { i, v -> assertEquals("embedding <$text>[$i]", v.jsonPrimitive.double, vector[i].toDouble(), 2e-4) }
        }
    }

    private fun record(o: JsonObject) = MicroRecord(
        titles = o.getValue("titles").strings(), kind = MicroKind.valueOf(o.getValue("kind").text()),
        year = o["year"].intOrNull(), authors = o.getValue("authors").strings(),
    )

    @Test fun `identity features and forests match Python`() {
        ready()
        val core = MicroPacks.loadCore(source)
        val model = MicroPacks.loadIdentity(source, core)
        var probabilityMismatch = 0
        var relationMismatch = 0
        val rows = golden.getValue("identity").jsonArray.map { it.jsonObject }
        for ((i, row) in rows.withIndex()) {
            val a = model.prepare(record(row.getValue("a").jsonObject)); val b = model.prepare(record(row.getValue("b").jsonObject))
            val features = IdentityFeatures.compute(a, b)
            row.getValue("features").jsonArray.forEachIndexed { j, v ->
                assertEquals("row $i feature ${IdentityFeatures.names[j]}", v.jsonPrimitive.double, features[j].toDouble(), 2e-4)
            }
            val verdict = model.compare(a, b)
            // Признаки энкодера считаются во float: в редком случае значение ложится по другую сторону порога дерева.
            if (abs(verdict.same - row.getValue("same").jsonPrimitive.double) > 2e-3) probabilityMismatch++
            val relation = checkNotNull(verdict.relation)
            val expectedRelation = row.getValue("relation").jsonArray
            assertEquals(expectedRelation.size, relation.size)
            if (relation.indices.any { j -> abs(relation[j] - expectedRelation[j].jsonPrimitive.double) > 2e-3 }) relationMismatch++
            // Порядок записей не должен менять ответ.
            assertEquals("symmetry row $i", verdict.same, model.compare(b, a).same, 1e-9)
        }
        assertTrue("probability mismatches: $probabilityMismatch of ${rows.size}", probabilityMismatch <= rows.size / 50)
        assertTrue("relation mismatches: $relationMismatch of ${rows.size}", relationMismatch <= rows.size / 50)
    }

    @Test fun `catalog parser language and genres match Python`() {
        ready()
        val core = MicroPacks.loadCore(source)
        val catalog = MicroPacks.loadCatalog(source, core)
        val parser = assertNotNull(catalog.parser).let { catalog.parser!! }
        var fieldMismatch = 0
        val rows = golden.getValue("parser").jsonArray.map { it.jsonObject }
        for (row in rows) {
            val raw = row.getValue("raw").text()
            val fields = row.getValue("fields").jsonObject
            val parsed = parser.parse(raw, NameDomain.values()[row.getValue("domain").jsonPrimitive.int])
            if (parsed == null) {
                if (fields.values.any { it !is JsonNull }) fieldMismatch++
                continue
            }
            fun same(name: String, actual: Any?): Boolean {
                val expected = fields[name]
                return if (expected == null || expected is JsonNull) actual == null else expected.jsonPrimitive.content == actual?.toString()
            }
            val ok = same("title", parsed.title) && same("alt_title", parsed.altTitle) && same("year", parsed.year) &&
                same("season", parsed.season) && same("episode", parsed.episode) && same("volume", parsed.volume) &&
                same("chapter", parsed.chapter) && same("author", parsed.author) && same("narrator", parsed.narrator)
            if (!ok) fieldMismatch++
        }
        assertTrue("parser mismatches: $fieldMismatch of ${rows.size}", fieldMismatch <= rows.size / 50)
        val language = catalog.language!!
        var languageMismatch = 0
        val titles = golden.getValue("language").jsonArray.map { it.jsonObject }
        for (row in titles) {
            val guess = language.classify(row.getValue("title").text())
            if (guess?.label != row.getValue("label").text() || abs(guess.probability - row.getValue("probability").jsonPrimitive.double) > 5e-3) languageMismatch++
        }
        assertTrue("language mismatches: $languageMismatch of ${titles.size}", languageMismatch <= titles.size / 50)
        val genres = catalog.genres!!
        for (row in golden.getValue("genres").jsonArray.map { it.jsonObject }) {
            val expected = row["tag"]?.takeIf { it !is JsonNull }?.text()
            assertEquals("genre <${row.getValue("raw").text()}>", expected, genres.normalize(row.getValue("raw").text())?.tagId)
        }
    }

    @Test fun `experience pack loads and the ranker starts at the current weights`() {
        ready()
        val models = ExperienceModels.load(source)
        val ranker = models.newRanker()!!
        val strong = floatArrayOf(0.9f, 0.9f, 0.8f, -1f, 0.5f); val weak = floatArrayOf(0.1f, 0.1f, 0.3f, -1f, 0.5f)
        assertEquals(ranker.priorScore(strong), ranker.score(strong), 0f)
        assertTrue(ranker.score(strong) > ranker.score(weak))
        repeat(40) { ranker.learn(weak, liked = true); ranker.learn(strong, liked = false) }
        assertTrue("the ranker follows the user's swipes", ranker.score(weak) > ranker.priorScore(weak))
        val restored = models.newRanker(ExperienceModels.decodeState(ExperienceModels.encodeState(ranker.state())))!!
        assertEquals(ranker.score(weak), restored.score(weak), 1e-6f)
        assertNotNull(models.genreSpace)
        assertTrue(models.genreSpace!!.smooth(mapOf("Isekai" to 1f)).getValue("Isekai") >= 1f)
    }

    @Test fun `damaged or mismatched packs fail closed`() {
        ready()
        val core = MicroPacks.loadCore(source)
        val broken = MicroAssetSource { path ->
            val bytes = File(File(root, "android-assets"), path).readBytes()
            if (path.endsWith("same.vmlf")) bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
            bytes.inputStream()
        }
        assertTrue("checksum mismatch must be rejected", runCatching { MicroPacks.loadIdentity(broken, core) }.isFailure)
        val otherEncoder = CorePack(core.version, core.encoder, "0".repeat(64))
        assertTrue("a pack built for another encoder must be rejected", runCatching { MicroPacks.loadIdentity(source, otherEncoder) }.isFailure)
        assertTrue(runCatching { MicroForest(ByteArray(64)) }.isFailure)
        assertTrue(runCatching { MicroTable(ByteArray(2048)) }.isFailure)
        assertTrue("a missing pack is an error for the loader, a fallback for the app",
            runCatching { MicroPacks.loadCatalog(MicroAssetSource { throw java.io.FileNotFoundException(it) }, core) }.isFailure)
        val damagedTaste = MicroAssetSource { path ->
            if (!path.startsWith("microml/experience/")) throw java.io.FileNotFoundException(path)
            if (path.endsWith("/taste.json")) "{}".byteInputStream() else source.open(path)
        }
        val experience = ExperienceModels.load(damagedTaste)
        assertNotNull(experience.genreSpace)
        assertEquals(null, experience.newRanker())
    }
}
