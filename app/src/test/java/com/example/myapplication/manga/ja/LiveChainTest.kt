package com.example.myapplication.manga.ja

import com.example.myapplication.data.local.JsonMapFileStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/**
 * Вся японская цепочка на живой сети: AniList -> поиск по сайтам -> бесплатные главы. Выключен по
 * умолчанию (нужна сеть и он дергает чужие сайты): включается переменной `VETRO_JA_LIVE=1`.
 * Нужен только разработчику, чтобы увидеть, на каком шаге цепочка останавливается.
 */
class LiveChainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val live = System.getenv("VETRO_JA_LIVE") == "1"

    /** Размер JPEG из первого кадра SOFn: в JVM-тестах нет Bitmap и ImageIO. */
    private fun jpegSize(data: ByteArray): Pair<Int, Int> {
        var i = 2
        while (i + 9 < data.size) {
            if (data[i] != 0xFF.toByte()) { i++; continue }
            val marker = data[i + 1].toInt() and 0xFF
            val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((data[i + 5].toInt() and 0xFF) shl 8) or (data[i + 6].toInt() and 0xFF)
                val w = ((data[i + 7].toInt() and 0xFF) shl 8) or (data[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + length
        }
        error("no SOF marker")
    }

    @Test
    fun new_giga_sites_search_list_and_open_a_free_chapter() = runBlocking {
        assumeTrue("VETRO_JA_LIVE is not set", live)
        val http = JaHttp(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        for (site in GigaSites.ALL.filter { it.key in setOf("ichicomi", "ourfeel") }) {
            val source = GigaViewerSource(site, http)
            val hits = source.search("の", 1).items
            println("${site.key}: search=${hits.size} first=${hits.firstOrNull()?.title}/${hits.firstOrNull()?.author}")
            val series = hits.firstOrNull() ?: continue
            val chapters = source.chapters(series)
            println("${site.key}: chapters=${chapters.size} newest=${chapters.maxByOrNull { it.number ?: -1.0 }?.title}")
            val free = chapters.firstOrNull() ?: continue
            val pages = source.pages(free)
            println("${site.key}: pages=${pages.size} decode=${pages.firstOrNull()?.decode}")
        }
    }

    @Test
    fun yanmaga_search_chapters_pages_and_a_downloaded_page() = runBlocking {
        assumeTrue("VETRO_JA_LIVE is not set", live)
        val http = JaHttp(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        val source = YanMagaSource(http)
        val series = source.search("リバース トー横キングダム", 1).items
        println("yanmaga: search=${series.size} first=${series.firstOrNull()?.title}/${series.firstOrNull()?.author}")
        val chapters = series.first().let { source.chapters(it) }
        println("yanmaga: chapters=${chapters.size} open=${chapters.count { !it.paid }}")
        val open = chapters.first { !it.paid }
        val pages = source.pages(open)
        println("yanmaga: pages=${pages.size} decode=${pages.firstOrNull()?.decode?.let { it::class.simpleName }}")
        val first = pages.first()
        val bytes = http.bytes(first.url, first.headers)
        val (width, height) = jpegSize(bytes)
        val name = first.url.substringBefore('?').substringAfterLast('/')
        val decode = first.decode as com.example.myapplication.manga.domain.PageDecode.SpeedBinb
        val layout = SpeedBinb.layout(name, decode.ctbl, decode.ptbl, width, height)
        println("yanmaga: downloaded ${bytes.size} bytes, ${width}x$height -> layout ${layout?.width}x${layout?.height} cells=${layout?.cells?.size}")
        // Платная глава и глава «после регистрации» страниц не отдаёт.
        val gated = chapters.firstOrNull { it.paid }
        if (gated != null) println("yanmaga: gated chapter '${gated.title}' pages=${runCatching { source.pages(gated).size }.getOrElse { "error $it" }}")
    }

    @Test
    fun yanmaga_long_series_lists_everything_and_closed_chapters_stay_closed() = runBlocking {
        assumeTrue("VETRO_JA_LIVE is not set", live)
        val http = JaHttp(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        val source = YanMagaSource(http)
        val series = source.search("ザ・ファブル The third secret", 1).items.first { it.title.contains("third secret", ignoreCase = true) }
        val chapters = source.chapters(series)
        val numbers = chapters.mapNotNull { it.number }
        println("yanmaga-long: ${series.title}: chapters=${chapters.size} numbered=${numbers.size} range=${numbers.minOrNull()}..${numbers.maxOrNull()} open=${chapters.count { !it.paid }}")
        println("yanmaga-long: open numbers=${chapters.filter { !it.paid }.mapNotNull { it.number }.sorted()}")
        val open = chapters.filter { !it.paid }.maxByOrNull { it.number ?: 0.0 }!!
        println("yanmaga-long: newest open '${open.title}' pages=${source.pages(open).size}")
        val closed = chapters.filter { it.paid }.maxByOrNull { it.number ?: 0.0 }!!
        println("yanmaga-long: newest closed '${closed.title}' pages=${runCatching { source.pages(closed).size }.getOrElse { "error: $it" }}")
    }

    @Test
    fun under_ninja_after_fourteen_chapters() = runBlocking {
        assumeTrue("VETRO_JA_LIVE is not set", live)
        val http = JaHttp(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        val titles = NativeTitleResolver(
            http,
            JsonMapFileStore(tmp.newFile("t2.json"), NativeEntry.serializer(), "t", logError = { m, e -> println("store: $m $e") }),
        )
        val native = titles.resolve("ninja", listOf("Under Ninja"))
        println("ninja native: $native")
        val sources = JapaneseSources.create(http)
        for (source in sources) {
            val verdict = runCatching {
                val items = source.search("アンダーニンジャ", 1).items
                val hit = items.firstOrNull { JaTitles.same(it.title, "アンダーニンジャ") && JaTitles.authorMatches(it.author, native?.authors.orEmpty()) }
                "results=${items.size} first=${items.firstOrNull()?.title}/${items.firstOrNull()?.author} hit=${hit?.key}"
            }.getOrElse { "ERROR $it" }
            println("ninja ${source.id.value}: $verdict")
        }
        val resolver = JaTailResolver(
            sources,
            titles,
            JsonMapFileStore(tmp.newFile("m2.json"), JaMatch.serializer(), "m", logError = { m, e -> println("store: $m $e") }),
            JsonMapFileStore(tmp.newFile("h2.json"), JaHealth.serializer(), "h", logError = { m, e -> println("store: $m $e") }),
        )
        val tail = resolver.resolve(TailRequest("ninja", listOf("Under Ninja"), lastKnownNumber = 14.0))
        println("ninja tail: ${tail.map { "${it.number}@${it.sourceId.value}" }}")
    }

    @Test
    fun kingdom_after_a_russian_chapter() = runBlocking {
        assumeTrue("VETRO_JA_LIVE is not set", live)
        val http = JaHttp(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        val titles = NativeTitleResolver(
            http,
            JsonMapFileStore(tmp.newFile("t.json"), NativeEntry.serializer(), "t", logError = { m, e -> println("store: $m $e") }),
        )
        val native = titles.resolve("anime-1", listOf("Царство", "Kingdom"))
        println("native title: $native")

        val resolver = JaTailResolver(
            JapaneseSources.create(http),
            titles,
            JsonMapFileStore(tmp.newFile("m.json"), JaMatch.serializer(), "m", logError = { m, e -> println("store: $m $e") }),
            JsonMapFileStore(tmp.newFile("h.json"), JaHealth.serializer(), "h", logError = { m, e -> println("store: $m $e") }),
        )
        // Пошагово по каждому источнику: что вернул поиск и что из этого сочли совпадением.
        for (source in JapaneseSources.create(http)) {
            val verdict = runCatching {
                val items = source.search("キングダム", 1).items
                val hit = items.firstOrNull { JaTitles.same(it.title, "キングダム") && JaTitles.authorMatches(it.author, native!!.authors) }
                "results=${items.size} titles=${items.take(3).map { it.title + "/" + it.author }} hit=${hit?.key}"
            }.getOrElse { "ERROR $it" }
            println("source ${source.id.value}: $verdict")
        }
        val tail = resolver.resolve(TailRequest("anime-1", listOf("Царство", "Kingdom"), lastKnownNumber = 836.0))
        println("tail: ${tail.size} chapters, first=${tail.firstOrNull()?.number} last=${tail.lastOrNull()?.number} source=${tail.firstOrNull()?.sourceId?.value}")
        val source = resolver.sourceById(tail.firstOrNull()?.sourceId?.value ?: "")
        if (source != null && tail.isNotEmpty()) {
            val pages = source.pages(tail.first())
            println("pages of chapter ${tail.first().number}: ${pages.size}, decode=${pages.firstOrNull()?.decode}")
        }
    }
}
