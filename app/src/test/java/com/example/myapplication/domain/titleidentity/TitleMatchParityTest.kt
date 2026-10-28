package com.example.myapplication.domain.titleidentity

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TitleMatchParityTest {
    @Test fun `Python and Kotlin feature and byte token vectors match`() {
        val root = File(System.getProperty("titlematch.fixtureRoot") ?: "tools/titlematch/.cache/benchmark-assets")
        val file = File(root, "feature-golden.json"); assumeTrue("Generate validation parity vectors first", file.isFile)
        val rows = JSONObject(file.readText()).getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val features = TitleMatchFeatures.compute(row.getString("a"), row.getString("b"), row.getString("kind_a"), row.getString("kind_b"),
                if (row.isNull("year_a")) null else row.getInt("year_a"), if (row.isNull("year_b")) null else row.getInt("year_b"))
            val expected = row.getJSONArray("features")
            for (j in features.indices) assertEquals("row $i feature ${TitleMatchFeatures.names[j]}", expected.getDouble(j), features[j].toDouble(), 1e-6)
            val encoded = row.getJSONArray("encoded_a")
            val actual = TitleMatchFeatures.encode(row.getString("a"))
            for (j in actual.indices) assertEquals("byte row $i index $j", encoded.getInt(j), actual[j])
        }
    }
    @Test fun `portable Kotlin forest matches Python LightGBM`() {
        val root = File(System.getProperty("titlematch.fixtureRoot") ?: "tools/titlematch/.cache/benchmark-assets")
        val vectors = File(root, "feature-golden.json"); val model = File(root, "model.vtmf")
        assumeTrue(vectors.isFile && model.isFile)
        val forest = PortableTitleForest(model.readBytes(), 1.0, 0.0)
        val rows = JSONObject(vectors.readText()).getJSONArray("vectors")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val a = row.getJSONArray("features")
            val features = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            assertEquals("forest probability row $i", row.getDouble("raw_probability"), forest.rawProbability(features), 1e-10)
        }
    }
    @Test fun `malformed model fails closed`() {
        assertTrue(runCatching { PortableTitleForest(ByteArray(30), 1.0, 0.0) }.isFailure)
    }
}
