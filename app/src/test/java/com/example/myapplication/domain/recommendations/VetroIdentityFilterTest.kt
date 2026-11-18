package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.local.VetroLink
import com.example.myapplication.data.local.VetroLinks
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.mediacatalog.VetroCatalog
import com.example.myapplication.mediacatalog.VetroHit
import com.example.myapplication.mediacatalog.VetroLibrary
import com.example.myapplication.mediacatalog.VetroLinkService
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.ExternalIds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VetroIdentityFilterTest {

    private val now = 1_800_000_000_000L

    private fun anime(id: String, type: MediaType = MediaType.ANIME) = Anime(
        id = id, title = id, episodes = 1, rating = 8f, imageFileName = null, orderIndex = 0, dateAdded = now, mediaType = type,
    )

    private fun entry(
        source: String,
        id: String,
        title: String,
        type: ContentType = ContentType.ANIME,
        ids: ExternalIds = ExternalIds(),
    ) = PoolEntry(
        result = ApiSearchResult(
            title = title, altTitle = null, posterUrl = "p", episodes = 0, description = "", type = "TV", genres = emptyList(), rating = null,
            source = source, categoryType = "ANIME", externalId = id, externalIds = ids,
        ),
        seedTitle = "seed",
        seedRating = 8f,
        contentType = type,
    )

    private class Links(vararg pairs: Pair<String, String?>) : VetroLinks {
        val rows = pairs.associate { (anime, vetro) -> anime to VetroLink(anime, vetro, 1L, null) }.toMutableMap()
        override suspend fun all() = rows.values.toList()
        override suspend fun vetroIds() = rows.values.mapNotNullTo(HashSet()) { it.vetroId }
        override suspend fun putAll(links: Collection<VetroLink>) { links.forEach { rows[it.animeId] = it } }
        override suspend fun delete(animeIds: Collection<String>) { animeIds.forEach { rows.remove(it) } }
    }

    private class Library(val items: List<Anime>) : VetroLibrary {
        override fun items() = items
        override fun hasCover(anime: Anime) = true
        override suspend fun setCover(animeId: String, fileName: String) = false
    }

    /** Каталог знает: AniList 16498 и MAL 16498 — A1, TMDB 1396 (сериал) — S1, TMDB 550 (фильм) — M1. */
    private class Catalog(val fail: Boolean = false) : VetroCatalog {
        val calls = ArrayList<Pair<List<String>, String?>>()
        private val known = mapOf(
            "anime" to mapOf("anilist:16498" to "A1", "mal:16498" to "A1", "anilist:1535" to "A2"),
            "tv" to mapOf("tmdb:1396" to "S1"),
            "movie" to mapOf("tmdb:550" to "M1"),
        )
        override suspend fun byExternalIds(keys: List<String>, kind: String?): Result<List<VetroHit>> {
            calls += keys to kind
            if (fail) return Result.failure(RuntimeException("offline"))
            val hits = keys.mapNotNull { key -> known[kind]?.get(key)?.let { id -> VetroHit(id, kind!!, listOf(id), null, 0, 0, key, emptyMap()) } }
            return Result.success(hits)
        }
        override suspend fun byTitle(query: String, kind: String, limit: Int) = Result.success(emptyList<VetroHit>())
        override suspend fun byIds(ids: List<String>) = Result.success(emptyList<VetroHit>())
        override suspend fun downloadCover(hit: VetroHit): Result<ByteArray>? = null
    }

    private fun filter(catalog: VetroCatalog, store: VetroLinks, library: List<Anime>) = VetroIdentityFilter(
        catalog, store,
        VetroLinkService(catalog, store, Library(library), storeCover = { _, _ -> null }, now = { now }, titleIntervalMs = 0),
    )

    @Test
    fun `a candidate under another service id but the same catalogue title as one in the collection is dropped`() = runBlocking {
        // В коллекции Attack on Titan под AniList 16498 (связь уже есть); кандидат пришёл из Shikimori/MAL под своим номером.
        val library = listOf(anime("mine"))
        val store = Links("mine" to "A1")
        val pool = listOf(
            entry("MAL", "16498", "Shingeki no Kyojin"),
            entry("AniList", "1535", "Death Note"),
        )
        val kept = filter(Catalog(), store, library).apply(pool, library)

        assertEquals(listOf("Death Note"), kept.map { it.result.title })
        assertEquals("A2", kept.single().result.externalIds.vetro)
    }

    @Test
    fun `films and series are looked up in their own database, so a series is not mistaken for a film with the same TMDB number`() = runBlocking {
        val library = listOf(anime("film", MediaType.MOVIE))
        val store = Links("film" to "M1")
        val catalog = Catalog()
        val pool = listOf(
            entry("TMDB", "550", "Fight Club (as a film)", ContentType.MOVIE),
            entry("TMDB", "1396", "Breaking Bad", ContentType.TV),
        )
        val kept = filter(catalog, store, library).apply(pool, library)

        assertEquals(listOf("Breaking Bad"), kept.map { it.result.title })
        assertEquals(setOf("movie", "tv"), catalog.calls.map { it.second }.toSet())
    }

    @Test
    fun `an unknown title is kept, a catalogue failure hides nothing, and the same ids are not asked twice`() = runBlocking {
        val library = listOf(anime("mine"))
        val store = Links("mine" to "A1")
        val pool = listOf(entry("MAL", "16498", "Same title"), entry("AniList", "424242", "Unknown"))

        val offline = filter(Catalog(fail = true), store, library).apply(pool, library)
        assertEquals(2, offline.size)

        val catalog = Catalog()
        val f = filter(catalog, store, library)
        f.apply(pool, library)
        val firstCalls = catalog.calls.size
        f.apply(pool, library)
        assertEquals("the second pass uses what the first learned", firstCalls, catalog.calls.size)
    }

    @Test
    fun `keys follow the type of the candidate and ignore ids from the other kind`() {
        val anime = entry("Shikimori", "16498", "x", ids = ExternalIds(anilist = 7, tmdb = 99))
        assertEquals(listOf("shikimori:16498", "anilist:7"), VetroIdentityFilter.keysOf(anime))
        val film = entry("TMDB", "550", "x", ContentType.MOVIE, ExternalIds(imdb = "tt0137523", anilist = 7))
        assertEquals(listOf("tmdb:550", "imdb:tt0137523"), VetroIdentityFilter.keysOf(film))
        assertTrue(VetroIdentityFilter.keysOf(entry("TMDB", "1 OR 1=1", "x", ContentType.TV)).isEmpty())
    }
}
