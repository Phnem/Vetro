package com.example.myapplication.microml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** Сверка Kotlin и Python для детектора заглушек обложек: признаки, оценка леса, порог, защита от подмены файла. */
class CoverPlaceholderModelTest {

    private val assets = File("src/main/assets")
    private val source = MicroAssetSource { path -> File(assets, path).inputStream() }

    private fun golden(): JsonObject =
        Json.parseToJsonElement(javaClass.getResourceAsStream("/microml/cover_golden.json")!!.readBytes().toString(Charsets.UTF_8)).jsonObject

    private fun floats(array: kotlinx.serialization.json.JsonArray): FloatArray = FloatArray(array.size) { array[it].jsonPrimitive.float }

    /** Та же картинка, что строит tools/microml/cover/export.py. */
    private fun synthetic(width: Int, height: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val r = (x * 7 + y * 3) % 256
        val g = (x * y) % 256
        val b = (x * 5 + y * 11) % 256
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun `features of a picture are the same numbers Python computes`() {
        val synth = golden().getValue("synthetic").jsonObject
        val width = synth.getValue("width").jsonPrimitive.int
        val height = synth.getValue("height").jsonPrimitive.int
        val expected = floats(synth.getValue("features").jsonArray)
        val actual = CoverFeatures.compute(synthetic(width, height), width, height)!!
        assertEquals(CoverFeatures.COUNT, actual.size)
        for (i in expected.indices) assertEquals("feature $i", expected[i], actual[i], 1e-6f)
    }

    @Test
    fun `the forest scores the golden vectors as Python does and the real placeholder is flagged`() {
        val model = CoverPlaceholderModel.load(source)
        val g = golden()
        val synth = g.getValue("synthetic").jsonObject
        val width = synth.getValue("width").jsonPrimitive.int
        val height = synth.getValue("height").jsonPrimitive.int
        val score = model.score(synthetic(width, height), width, height)!!
        assertEquals(synth.getValue("score").jsonPrimitive.float, score, 1e-5f)
        assertFalse("a random-looking picture is not a placeholder", model.isPlaceholder(score))

        // Вектор признаков настоящей заглушки (одна из сохранённых версий): модель обязана её узнать.
        val placeholder = g.getValue("placeholder").jsonObject
        val forest = MicroForest(File(assets, "microml/cover/cover_placeholder.vmlf").readBytes())
        val placeholderScore = forest.predict(floats(placeholder.getValue("features").jsonArray))[0]
        assertEquals(placeholder.getValue("score").jsonPrimitive.float, placeholderScore, 1e-5f)
        assertTrue(model.isPlaceholder(placeholderScore))
    }

    @Test
    fun `a picture too small to judge is not scored`() {
        val model = CoverPlaceholderModel.load(source)
        assertNull(model.score(IntArray(15 * 40), 15, 40))
        assertNull(CoverFeatures.compute(IntArray(10), 40, 40))
        assertNotNull(CoverFeatures.compute(IntArray(16 * 16), 16, 16))
    }

    @Test
    fun `a model file that does not match its manifest is refused`() {
        val real = File(assets, "microml/cover/cover_placeholder.vmlf").readBytes()
        val tampered = real.copyOf().also { it[real.size / 2] = (it[real.size / 2] + 1).toByte() }
        val broken = MicroAssetSource { path ->
            if (path.endsWith("cover_placeholder.vmlf")) ByteArrayInputStream(tampered) else File(assets, path).inputStream()
        }
        try {
            CoverPlaceholderModel.load(broken)
            fail("the checksum must be verified")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("checksum"))
        }
    }
}
