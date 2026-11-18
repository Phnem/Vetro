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

class StockCoverRepairTest {

    private val now = 1_800_000_000_000L

    private fun anime(id: String, image: String?, anilist: Int? = null) = Anime(
        id = id, title = id, episodes = 1, rating = 0f, imageFileName = image, orderIndex = 0, dateAdded = now,
        mediaType = MediaType.ANIME, anilistId = anilist,
    )

    private fun hit(id: String, cover: Boolean, via: String? = null) = VetroHit(id, "anime", listOf(id), null, if (cover) 1 else 0, 3, via, emptyMap())

    private class Links(vararg pairs: Pair<String, String>) : VetroLinks {
        val rows = pairs.associate { (a, v) -> a to VetroLink(a, v, 1L, null) }.toMutableMap()
        override suspend fun all() = rows.values.toList()
        override suspend fun vetroIds() = rows.values.mapNotNullTo(HashSet()) { it.vetroId }
        override suspend fun putAll(links: Collection<VetroLink>) { links.forEach { rows[it.animeId] = it } }
        override suspend fun delete(animeIds: Collection<String>) { animeIds.forEach { rows.remove(it) } }
    }

    private class Library(val items: MutableList<Anime>) : VetroLibrary {
        val covers = HashMap<String, String?>().also { m -> items.forEach { m[it.id] = it.imageFileName } }
        val removedFiles = ArrayList<String>()
        override fun items() = items.map { it.copy(imageFileName = covers[it.id]) }
        override fun hasCover(anime: Anime) = covers[anime.id] != null
        override suspend fun setCover(animeId: String, fileName: String) = false
        override fun coverFile(anime: Anime): String? = covers[anime.id]?.let { "/files/$it" }
        override suspend fun replaceCover(animeId: String, oldName: String, newName: String): Boolean {
            if (covers[animeId] != oldName) return false
            covers[animeId] = newName; removedFiles += oldName; return true
        }
        override suspend fun dropCover(animeId: String, oldName: String): Boolean {
            if (covers[animeId] != oldName) return false
            covers[animeId] = null; removedFiles += oldName; return true
        }
    }

    private class Catalog(
        val byId: Map<String, VetroHit> = emptyMap(),
        val byExt: Map<String, VetroHit> = emptyMap(),
        val offline: Boolean = false,
    ) : VetroCatalog {
        override suspend fun byExternalIds(keys: List<String>, kind: String?): Result<List<VetroHit>> =
            if (offline) Result.failure(RuntimeException("offline")) else Result.success(keys.mapNotNull { k -> byExt[k]?.copy(via = k) })
        override suspend fun byTitle(query: String, kind: String, limit: Int): Result<List<VetroHit>> =
            if (offline) Result.failure(RuntimeException("offline")) else Result.success(emptyList())
        override suspend fun byIds(ids: List<String>): Result<List<VetroHit>> =
            if (offline) Result.failure(RuntimeException("offline")) else Result.success(ids.mapNotNull { byId[it] })
        override suspend fun downloadCover(hit: VetroHit): Result<ByteArray>? = if (hit.hasCover) Result.success(byteArrayOf(1)) else null
    }

    private class Checked : CheckedCovers {
        val names = HashSet<String>()
        override fun isChecked(name: String) = name in names
        override fun markChecked(names: Collection<String>) { this.names.addAll(names) }
    }

    private fun repair(
        library: VetroLibrary, links: VetroLinks, catalog: VetroCatalog, checked: CheckedCovers, stockFiles: Set<String>,
        asked: MutableList<String> = ArrayList(),
    ): StockCoverRepair {
        val linker = VetroLinkService(catalog, links, library, storeCover = { _, _ -> null }, now = { now }, titleIntervalMs = 0)
        return StockCoverRepair(
            library, links, catalog, linker,
            isStockFile = { path -> asked += path; path.substringAfterLast('/') in stockFiles },
            storeCover = { _, id -> "new_$id.webp" },
            checked = checked,
        )
    }

    @Test
    fun `a placeholder is replaced by the catalogue cover, a real cover is left and remembered as checked`() = runBlocking {
        val library = Library(mutableListOf(anime("death", "stock.webp"), anime("fine", "real.webp")))
        val links = Links("death" to "A00000051")
        val catalog = Catalog(byId = mapOf("A00000051" to hit("A00000051", cover = true)))
        val checked = Checked()
        val out = repair(library, links, catalog, checked, setOf("stock.webp")).run()

        assertEquals(StockCoverRepair.RepairResult(stock = 1, replaced = 1, dropped = 0, postponed = 0), out)
        assertEquals("new_death.webp", library.covers["death"])
        assertEquals("real.webp", library.covers["fine"])
        assertEquals(listOf("stock.webp"), library.removedFiles)
        assertTrue("real.webp" in checked.names && "new_death.webp" in checked.names)
    }

    @Test
    fun `a placeholder with no cover in the catalogue is removed so the usual cover search takes over`() = runBlocking {
        val library = Library(mutableListOf(anime("a", "stock.webp")))
        val catalog = Catalog(byId = mapOf("A1" to hit("A1", cover = false)))
        val out = repair(library, Links("a" to "A1"), catalog, Checked(), setOf("stock.webp")).run()

        assertEquals(1, out.dropped)
        assertNull(library.covers["a"])
    }

    @Test
    fun `a placeholder of a title that is not linked yet is found by its ids first`() = runBlocking {
        val library = Library(mutableListOf(anime("a", "stock.webp", anilist = 1535)))
        val links = Links()
        val catalog = Catalog(byExt = mapOf("anilist:1535" to hit("A00000051", cover = true)))
        val out = repair(library, links, catalog, Checked(), setOf("stock.webp")).run()

        assertEquals(1, out.replaced)
        assertEquals("A00000051", links.rows.getValue("a").vetroId)
    }

    @Test
    fun `when the catalogue is unreachable nothing is touched and the file stays unchecked`() = runBlocking {
        val library = Library(mutableListOf(anime("a", "stock.webp")))
        val checked = Checked()
        val out = repair(library, Links("a" to "A1"), Catalog(offline = true), checked, setOf("stock.webp")).run()

        assertEquals(1, out.postponed)
        assertEquals("stock.webp", library.covers["a"])
        assertTrue("stock.webp" !in checked.names)
        assertTrue(library.removedFiles.isEmpty())
    }

    @Test
    fun `files already checked are not decoded again`() = runBlocking {
        val library = Library(mutableListOf(anime("a", "seen.webp"), anime("b", "new.webp")))
        val checked = Checked().apply { names += "seen.webp" }
        val asked = ArrayList<String>()
        repair(library, Links(), Catalog(), checked, emptySet(), asked).run()

        assertEquals(listOf("/files/new.webp"), asked)
    }
}
