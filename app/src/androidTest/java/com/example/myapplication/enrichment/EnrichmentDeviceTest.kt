package com.example.myapplication.enrichment

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.enrichment.title.TitleEnrichmentRepository
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.AnimeSkipClient
import com.example.myapplication.network.enrichment.BookBrainzClient
import com.example.myapplication.network.enrichment.ExternalSkipKind
import com.example.myapplication.network.enrichment.ITunesClient
import com.example.myapplication.network.enrichment.IntroDbClient
import com.example.myapplication.network.enrichment.OpenLibraryClient
import com.example.myapplication.network.enrichment.TvMazeClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Живые запросы слоя обогащения на устройстве через граф Koin приложения. Только чтение открытых
 * API без ключей; результат пишется в logcat (тег EnrichmentDevice) для ручной сверки.
 */
@RunWith(AndroidJUnit4::class)
class EnrichmentDeviceTest {
    private val koin get() = GlobalContext.get()

    private fun log(msg: String) = Log.i(TAG, msg)

    private fun <T> LookupResult<T>.found(): T {
        assertTrue("ожидался Found, пришло $this", this is LookupResult.Found)
        return (this as LookupResult.Found).value
    }

    @Test fun frierenAnimeEnrichment() = runBlocking<Unit> {
        val repo = koin.get<TitleEnrichmentRepository>()
        val frieren = Anime(
            id = "device-frieren", title = "Frieren", episodes = 28, rating = 0f, imageFileName = null,
            orderIndex = 0, dateAdded = 0, anilistId = 154587, shikimoriId = 52991, tmdbId = 209867,
            mediaType = MediaType.ANIME,
        )
        val e = repo.load(frieren, AppLanguage.RU)
        log("frieren: logo=${e.logo?.url} lang=${e.logo?.language} trailer=${e.trailer?.key}/${e.trailer?.source} " +
            "next=${e.nextRelease} dubs=${e.russianDubs.take(6)} prov=${e.provenance}")
        assertNotNull("у Frieren на TMDb есть логотипы", e.logo)
        assertNotNull("трейлер AniList/Shikimori/TMDb", e.trailer)
        assertTrue("у Shikimori есть русские фандаберы", e.russianDubs.isNotEmpty())
    }

    @Test fun breakingBadAndInception() = runBlocking<Unit> {
        val repo = koin.get<TitleEnrichmentRepository>()
        val bb = Anime(
            id = "device-bb", title = "Breaking Bad", episodes = 62, rating = 0f, imageFileName = null,
            orderIndex = 0, dateAdded = 0, tmdbId = 1396, mediaType = MediaType.SERIES,
        )
        val bbE = repo.load(bb, AppLanguage.EN)
        log("breakingBad: logo=${bbE.logo?.url} backdrop=${bbE.backdrop?.url} next=${bbE.nextRelease} prov=${bbE.provenance}")
        assertNotNull(bbE.logo)
        // Сериал закончен — никакого «следующего эпизода», и ритм не выдумывается.
        assertEquals(null, bbE.nextRelease)

        val inception = Anime(
            id = "device-inception", title = "Inception", episodes = 1, rating = 0f, imageFileName = null,
            orderIndex = 0, dateAdded = 0, tmdbId = 27205, mediaType = MediaType.MOVIE,
        )
        val t0 = System.nanoTime()
        val inc = repo.load(inception, AppLanguage.RU)
        val first = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val again = repo.load(inception, AppLanguage.RU)
        val second = (System.nanoTime() - t1) / 1_000_000
        log("inception: logo=${inc.logo?.url} lang=${inc.logo?.language} trailer=${inc.trailer?.key} first=${first}ms cached=${second}ms")
        assertNotNull(inc.logo)
        assertEquals("второй раз — из файлового кэша, тот же результат", inc.logo?.url, again.logo?.url)
        val cacheDir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "enrichment")
        assertTrue("кэш пишется на диск", cacheDir.listFiles().orEmpty().isNotEmpty())
    }

    @Test fun skipSources() = runBlocking<Unit> {
        val intro = koin.get<IntroDbClient>().episode("tt0903747", 1, 1)
        log("introdb BB s1e1: $intro")
        val animeSkip = koin.get<AnimeSkipClient>().episodesByAnilist(154587)
        when (animeSkip) {
            is LookupResult.Found -> {
                val eps = animeSkip.value
                log("anime-skip frieren: ${eps.size} versions; first=${eps.firstOrNull()}")
                assertTrue(eps.isNotEmpty())
                assertTrue(eps.flatMap { it.segments }.any { it.kind == ExternalSkipKind.OPENING })
            }
            // Общий client id лимитирован — отказ квоты допустим, но не падение.
            else -> log("anime-skip frieren: $animeSkip")
        }
        assertTrue(intro is LookupResult.Found || intro is LookupResult.NoMatch)
    }

    @Test fun tvMazeSchedule() = runBlocking<Unit> {
        val show = koin.get<TvMazeClient>().show("tt0903747", null).found()
        log("tvmaze BB: ${show.name} status=${show.status} prev=${show.previous} next=${show.next}")
        assertEquals("Ended", show.status)
    }

    @Test fun books() = runBlocking<Unit> {
        val works = koin.get<OpenLibraryClient>().searchWorks("The Three-Body Problem", "Liu Cixin").found()
        val work = works.first()
        log("openlibrary: ${work.key} ${work.title} editions=${work.editionCount}")
        val editions = koin.get<OpenLibraryClient>().editions(work.key).found()
        log("openlibrary editions: ${editions.size}, rus=${editions.filter { "rus" in it.languages }.map { it.title to it.isbn13 }}")
        assertTrue(editions.isNotEmpty())

        val itunes = koin.get<ITunesClient>().audiobooks("Project Hail Mary", "us").found()
        log("itunes: ${itunes.take(2).map { "${it.title} / ${it.author} / ${it.artworkUrl}" }}")
        assertTrue(itunes.isNotEmpty())

        val bb = koin.get<BookBrainzClient>().searchWorks("Dune")
        log("bookbrainz Dune: $bb")
    }

    private companion object {
        const val TAG = "EnrichmentDevice"
    }
}
