package com.example.myapplication.microml

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.domain.titleidentity.TitleIdentityDecision
import com.example.myapplication.domain.titleidentity.TitleIdentityResult
import com.example.myapplication.microml.MicroMlEngine.Feature
import com.example.myapplication.microml.MicroMlEngine.Pack
import com.example.myapplication.microml.MicroMlEngine.PackState
import com.example.myapplication.micromlintegration.MicroMlBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicroMlDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packageControlsFallBackIndependently() = runBlocking<Unit> {
        assumeTrue(context.packageName.endsWith(".micromlsmoke"))
        val engine = MicroMlEngine(context)
        try {
            assertEquals(List(3) { PackState.READY }, engine.status().map { it.state })
            assertFalse(Feature.entries.any(engine::isActive))

            for (kind in MicroKind.entries) {
                val query = MicroRecord(listOf("Dune"), kind)
                val verified = MicroRecord(listOf("Дюна"), kind)
                val candidates = listOf(query, verified, MicroRecord(listOf("Dune Messiah"), kind))
                val proof = TitleIdentityResult(TitleIdentityDecision.SAME_ENTITY, "ID_MATCH", verified = true)
                assertSame(candidates, MicroMlBridge.rerank(engine, query, candidates, { it }))
                engine.setActive(Feature.IDENTITY_SEARCH_RERANK, true)
                val result = MicroMlBridge.rerank(engine, query, candidates, { it }, mapOf(verified to proof))
                assertEquals(verified, result.first())
                assertEquals(candidates.size, result.size)
                assertEquals(candidates.toSet(), result.toSet())
                engine.setActive(Feature.IDENTITY_SEARCH_RERANK, false)
            }

            engine.setPackEnabled(Pack.IDENTITY, false)
            assertNull(engine.identity())
            assertNotNull(engine.catalog())
            assertNotNull(engine.experience())

            engine.setPackEnabled(Pack.IDENTITY, true)
            engine.setRolledBack(Pack.CATALOG, true)
            assertNull(engine.catalog()) // First release has no previous catalog.
            assertNotNull(engine.identity())
            assertNotNull(engine.experience())

            repeat(MicroMlEngine.MAX_FAILURES) {
                assertNull(engine.guarded(Pack.EXPERIENCE) { error("simulated inference failure") })
            }
            assertNull(engine.experience())
            assertNotNull(engine.identity())
            engine.setRolledBack(Pack.CATALOG, false)
            assertNotNull(engine.catalog())
        } finally {
            engine.setActive(Feature.IDENTITY_SEARCH_RERANK, false)
            engine.setPackEnabled(Pack.IDENTITY, true)
            engine.setRolledBack(Pack.CATALOG, false)
        }
    }

    @Test fun profileBundledModels() = runBlocking<Unit> {
        assumeTrue(context.packageName.endsWith(".micromlsmoke"))
        val engine = MicroMlEngine(context)
        val base = Debug.getPss()
        val identity = load("identity") { checkNotNull(engine.identity()) }
        val afterIdentity = Debug.getPss()
        val catalog = load("catalog") { checkNotNull(engine.catalog()) }
        val afterCatalog = Debug.getPss()
        val experience = load("experience") { checkNotNull(engine.experience()) }
        val afterExperience = Debug.getPss()
        Log.i(TAG, "pss_kib baseline=$base identity=$afterIdentity catalog=$afterCatalog experience=$afterExperience")

        val examples = listOf(
            MicroRecord(listOf("Cowboy Bebop", "カウボーイビバップ"), MicroKind.ANIME, 1998) to "Ковбой Бибоп",
            MicroRecord(listOf("鋼の錬金術師", "Fullmetal Alchemist"), MicroKind.MANGA) to "Стальной алхимик",
            MicroRecord(listOf("Spirited Away", "千と千尋の神隠し"), MicroKind.MOVIE, 2001) to "Унесённые призраками",
            MicroRecord(listOf("Better Call Saul"), MicroKind.SERIES, 2015) to "Лучше звоните Солу",
            MicroRecord(listOf("Dune"), MicroKind.BOOK_WORK, authors = listOf("Frank Herbert")) to "Дюна",
            MicroRecord(listOf("The Hobbit"), MicroKind.AUDIOBOOK, authors = listOf("J. R. R. Tolkien")) to "Хоббит",
        )
        measure("identity_pair") { i ->
            val (record, alias) = examples[i % examples.size]
            val other = record.copy(titles = listOf(alias))
            identity.compare(record, other).same
        }
        measure("identity_search_20") { i ->
            val (record, alias) = examples[i % examples.size]
            val query = identity.prepare(record.copy(titles = listOf(alias)))
            (0 until 20).sumOf { identity.score(query, identity.prepare(examples[it % examples.size].first)) }
        }
        val parser = checkNotNull(catalog.parser)
        val language = checkNotNull(catalog.language)
        val genres = checkNotNull(catalog.genres)
        measure("catalog_folder") { parser.parse("Герберт Фрэнк - Дюна [читает Иван Петров, 2020]", NameDomain.AUDIOBOOK)?.confidence ?: 0f }
        measure("catalog_language") { i -> language.classify(examples[i % examples.size].second)?.probability ?: 0f }
        measure("catalog_genre") { genres.normalize("Ciencia ficción")?.score ?: 0f }
        val space = checkNotNull(experience.genreSpace)
        val ranker = checkNotNull(experience.newRanker())
        val profile = mapOf("Drama" to 0.8f, "Comedy" to 0.7f, "Horror" to 0.3f)
        val signals = FloatArray(5) { 0.8f - it * 0.1f }
        measure("experience_profile_and_40_scores") {
            space.smooth(profile).size + (0 until 40).sumOf { ranker.score(signals).toDouble() }
        }
    }

    private suspend fun <T> load(name: String, block: suspend () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        return block().also { Log.i(TAG, "${name}_cold_ms=${(SystemClock.elapsedRealtimeNanos() - start) / 1e6}") }
    }

    private fun measure(name: String, block: (Int) -> Number) {
        repeat(50) { block(it) }
        var checksum = 0.0
        val times = LongArray(200)
        val cpu = Debug.threadCpuTimeNanos()
        for (i in times.indices) {
            val start = SystemClock.elapsedRealtimeNanos()
            checksum += block(i).toDouble()
            times[i] = SystemClock.elapsedRealtimeNanos() - start
        }
        val cpuPerCall = (Debug.threadCpuTimeNanos() - cpu) / 1e6 / times.size
        times.sort()
        Log.i(TAG, "$name p50_ms=${times[99] / 1e6} p95_ms=${times[189] / 1e6} cpu_ms=$cpuPerCall checksum=$checksum")
    }

    companion object {
        private const val TAG = "MicroMlBench"
    }
}
