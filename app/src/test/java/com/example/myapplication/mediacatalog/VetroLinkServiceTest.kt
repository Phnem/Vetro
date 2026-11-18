package com.example.myapplication.mediacatalog

import com.example.myapplication.data.local.VetroLink
import com.example.myapplication.data.local.VetroLinks
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VetroLinkServiceTest {

    private val now = 1_800_000_000_000L

    private fun anime(
        id: String,
        title: String = id,
        type: MediaType = MediaType.ANIME,
        anilist: Int? = null,
        mal: Int? = null,
        tmdb: Int? = null,
        imdb: String? = null,
        year: Int? = null,
        image: String? = null,
        titleEn: String? = null,
    ) = Anime(
        id = id, title = title, episodes = 1, rating = 0f, imageFileName = image, orderIndex = 0, dateAdded = now,
        mediaType = type, anilistId = anilist, malId = mal, tmdbId = tmdb, imdbId = imdb, releaseYear = year, titleEn = titleEn,
    )

    private fun hit(
        id: String,
        kind: String,
        vararg titles: String,
        via: String? = null,
        year: Int? = null,
        ext: Map<String, String> = emptyMap(),
        cover: Boolean = false,
    ) = VetroHit(id, kind, titles.toList(), year, if (cover) 1 else 0, 7, via, ext)

    private class FakeLinks : VetroLinks {
        val rows = LinkedHashMap<String, VetroLink>()
        override suspend fun all() = rows.values.toList()
        override suspend fun vetroIds() = rows.values.mapNotNullTo(HashSet()) { it.vetroId }
        override suspend fun putAll(links: Collection<VetroLink>) { links.forEach { rows[it.animeId] = it } }
        override suspend fun delete(animeIds: Collection<String>) { animeIds.forEach { rows.remove(it) } }
    }

    private class FakeLibrary(val items: MutableList<Anime>) : VetroLibrary {
        val covers = HashMap<String, String>()
        override fun items() = items.toList()
        override fun hasCover(anime: Anime) = anime.imageFileName != null || covers.containsKey(anime.id)
        override suspend fun setCover(animeId: String, fileName: String): Boolean {
            if (covers.containsKey(animeId)) return false
            covers[animeId] = fileName
            return true
        }
    }

    private class FakeCatalog(
        val onExt: (List<String>, String?) -> Result<List<VetroHit>> = { _, _ -> Result.success(emptyList()) },
        val onTitle: (String, String) -> Result<List<VetroHit>> = { _, _ -> Result.success(emptyList()) },
        val onId: (List<String>) -> Result<List<VetroHit>> = { Result.success(emptyList()) },
    ) : VetroCatalog {
        val extCalls = ArrayList<Pair<List<String>, String?>>()
        val titleCalls = ArrayList<Pair<String, String>>()
        override suspend fun byExternalIds(keys: List<String>, kind: String?): Result<List<VetroHit>> { extCalls += keys to kind; return onExt(keys, kind) }
        override suspend fun byTitle(query: String, kind: String, limit: Int): Result<List<VetroHit>> { titleCalls += query to kind; return onTitle(query, kind) }
        override suspend fun byIds(ids: List<String>) = onId(ids)
        override suspend fun downloadCover(hit: VetroHit): Result<ByteArray>? = if (hit.hasCover) Result.success(byteArrayOf(1, 2, 3)) else null
    }

    private fun service(catalog: VetroCatalog, store: VetroLinks, library: VetroLibrary) =
        VetroLinkService(catalog, store, library, storeCover = { _, id -> "img_$id.webp" }, now = { now }, titleIntervalMs = 0)

    @Test
    fun `ids of other services link a title to its catalogue record, one request per kind and batch`() = runBlocking {
        val aot = hit("A00000050", "anime", "Attack on Titan", via = "anilist:16498")
        val breaking = hit("S00000002", "tv", "Breaking Bad", via = "tmdb:1396")
        val catalog = FakeCatalog(onExt = { keys, kind ->
            Result.success(listOfNotNull(aot.takeIf { "anilist:16498" in keys && kind == "anime" }, breaking.takeIf { "tmdb:1396" in keys && kind == "tv" }))
        })
        val store = FakeLinks()
        val library = FakeLibrary(mutableListOf(
            anime("a", anilist = 16498),
            anime("b", type = MediaType.SERIES, tmdb = 1396),
            anime("m", type = MediaType.MANGA, anilist = 1),
        ))
        val out = service(catalog, store, library).run() as LinkOutcome.Done

        assertEquals("A00000050", store.rows.getValue("a").vetroId)
        assertEquals("anilist:16498", store.rows.getValue("a").via)
        assertEquals("S00000002", store.rows.getValue("b").vetroId)
        assertNull("manga has no catalogue database", store.rows["m"])
        assertEquals(2, out.linked)
        assertEquals(setOf("anime", "tv"), catalog.extCalls.map { it.second }.toSet())
    }

    @Test
    fun `a title with no ids is found by name inside its own database, and a different year is not the same film`() = runBlocking {
        val right = hit("M00000007", "movie", "Dune", year = 2021)
        val older = hit("M00000001", "movie", "Dune", year = 1984)
        val catalog = FakeCatalog(onTitle = { _, kind -> Result.success(if (kind == "movie") listOf(older, right) else emptyList()) })
        val store = FakeLinks()
        val library = FakeLibrary(mutableListOf(anime("d", title = "Dune", type = MediaType.MOVIE, year = 2021)))
        service(catalog, store, library).run()

        assertEquals("M00000007", store.rows.getValue("d").vetroId)
        assertEquals("title", store.rows.getValue("d").via)
    }

    @Test
    fun `a record whose id in the same service differs is a different title even when the name matches`() {
        val other = hit("A00000900", "anime", "Fate", ext = mapOf("anilist" to "999"))
        assertNull(VetroLinkService.pickByTitle(anime("x", title = "Fate", anilist = 111), listOf(other)))
        val same = hit("A00000901", "anime", "Fate/Zero", ext = mapOf("anilist" to "111"))
        assertEquals("A00000901", VetroLinkService.pickByTitle(anime("x", title = "Something else", anilist = 111), listOf(same))?.id)
    }

    @Test
    fun `a title not in the catalogue is marked and not asked again for a week, a network failure marks nothing`() = runBlocking {
        val store = FakeLinks()
        val library = FakeLibrary(mutableListOf(anime("n", title = "No Such Show")))
        service(FakeCatalog(), store, library).run()
        assertNull(store.rows.getValue("n").vetroId)
        assertTrue(!VetroLinkService.needsCheck(store.rows.getValue("n"), now + 1000))
        assertTrue(VetroLinkService.needsCheck(store.rows.getValue("n"), now + VetroLinkService.RETRY_MS + 1))

        val offlineStore = FakeLinks()
        val offline = FakeCatalog(onExt = { _, _ -> Result.failure(RuntimeException("offline")) })
        val out = service(offline, offlineStore, FakeLibrary(mutableListOf(anime("a", anilist = 5)))).run()
        assertEquals(LinkOutcome.Unavailable, out)
        assertTrue(offlineStore.rows.isEmpty())

        val titleFails = FakeCatalog(onTitle = { _, _ -> Result.failure(RuntimeException("429")) })
        val failedStore = FakeLinks()
        service(titleFails, failedStore, FakeLibrary(mutableListOf(anime("t", title = "Whatever")))).run()
        assertTrue("a failed lookup must not be recorded as 'not found'", failedStore.rows.isEmpty())
    }

    @Test
    fun `a cover the title lacks is pulled from the catalogue, one it already has is left alone`() = runBlocking {
        val withCover = hit("A00000050", "anime", "Attack on Titan", via = "anilist:16498", cover = true)
        val other = hit("A00000051", "anime", "Death Note", via = "anilist:1535", cover = true)
        val catalog = FakeCatalog(onExt = { _, _ -> Result.success(listOf(withCover, other)) })
        val library = FakeLibrary(mutableListOf(anime("a", anilist = 16498), anime("b", anilist = 1535, image = "have.webp")))
        val out = service(catalog, FakeLinks(), library).run() as LinkOutcome.Done

        assertEquals(mapOf("a" to "img_a.webp"), library.covers)
        assertEquals(1, out.covers)
    }

    @Test
    fun `a title that is already linked is skipped and links of removed titles are pruned`() = runBlocking {
        val store = FakeLinks().apply {
            rows["a"] = VetroLink("a", "A00000050", now, "anilist:16498")
            rows["gone"] = VetroLink("gone", "A00000099", now, null)
        }
        val catalog = FakeCatalog()
        service(catalog, store, FakeLibrary(mutableListOf(anime("a", anilist = 16498)))).run()

        assertTrue(catalog.extCalls.isEmpty())
        assertNull(store.rows["gone"])
        assertEquals("A00000050", store.rows.getValue("a").vetroId)
    }

    @Test
    fun `external keys follow the order of trust and Shikimori doubles as MAL`() {
        assertEquals(
            listOf("anilist" to "1", "mal" to "2"),
            VetroLinkService.externalKeys(anime("x", anilist = 1, mal = 2)),
        )
        assertEquals(
            listOf("tmdb" to "550", "imdb" to "tt0137523"),
            VetroLinkService.externalKeys(anime("f", type = MediaType.MOVIE, tmdb = 550, imdb = "tt0137523")),
        )
    }
}
