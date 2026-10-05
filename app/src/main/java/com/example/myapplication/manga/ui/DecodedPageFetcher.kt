package com.example.myapplication.manga.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.PageDecode
import com.example.myapplication.manga.ja.PageDecoder
import com.example.myapplication.manga.ja.SpeedBinb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.koin.mp.KoinPlatform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Страница, которую нужно не просто скачать, а ещё и восстановить (см. [PageDecode]). Обычные
 * страницы по-прежнему идут в Coil строкой-URL: этот тип только для японских источников.
 */
data class DecodedPageRequest(
    val url: String,
    val headers: Map<String, String>,
    val decode: PageDecode,
)

/** Что отдать Coil для страницы: строку-URL или запрос с восстановлением. */
fun MangaPage.coilData(): Any = decode?.let { DecodedPageRequest(url, headers, it) } ?: url

/**
 * Скачивает страницу, восстанавливает и кладёт результат в собственный дисковый кэш: сетевого
 * кэша Coil для такого запроса нет (картинка меняется после загрузки), а повторно качать и
 * пересобирать страницу при каждом пролистывании было бы расточительно.
 */
object DecodedPageLoader {

    private const val CACHE_DIR = "manga_ja_pages"
    private const val MAX_CACHE_BYTES = 192L * 1024 * 1024

    private val client: OkHttpClient get() = KoinPlatform.getKoin().get()

    /** Файл с восстановленной страницей; при отсутствии качает и готовит. */
    suspend fun file(context: Context, request: DecodedPageRequest): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, cacheName(request))
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val decoded = decodedBytes(request)
        val temp = File(dir, target.name + ".part")
        temp.writeBytes(decoded)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("cannot store decoded page")
        }
        prune(dir)
        target
    }

    /** Восстановленные байты без кэша - для сервиса перевода, который декодирует сам. */
    suspend fun bytes(request: DecodedPageRequest): ByteArray = withContext(Dispatchers.IO) { decodedBytes(request) }

    private fun decodedBytes(request: DecodedPageRequest): ByteArray {
        val raw = download(request)
        return when (val mode = request.decode) {
            is PageDecode.Xor -> PageDecoder.xor(raw, mode.keyHex)
            PageDecode.GigaScramble -> descramble(raw)
            is PageDecode.SpeedBinb -> rearrange(raw, request.url, mode)
        }
    }

    private fun download(request: DecodedPageRequest): ByteArray {
        val http = Request.Builder().url(request.url)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(http).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("empty page")
        }
    }

    private fun descramble(raw: ByteArray): ByteArray {
        val source = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw IOException("undecodable page")
        val cells = PageDecoder.gigaCells(source.width, source.height)
        if (cells.isEmpty()) return raw // слишком маленькая картинка: переставлять нечего
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Края, не кратные размеру блока, остаются на месте: сначала кладём оригинал целиком.
        canvas.drawBitmap(source, 0f, 0f, null)
        for (c in cells) {
            canvas.drawBitmap(
                source,
                Rect(c.srcX, c.srcY, c.srcX + c.width, c.srcY + c.height),
                Rect(c.dstX, c.dstY, c.dstX + c.width, c.dstY + c.height),
                null,
            )
        }
        source.recycle()
        val out = ByteArrayOutputStream(raw.size)
        result.compress(Bitmap.CompressFormat.JPEG, 95, out)
        result.recycle()
        return out.toByteArray()
    }

    /** SpeedBinb: куски с полями переносятся на места по раскладке, итог меньше исходной картинки на поля. */
    private fun rearrange(raw: ByteArray, url: String, mode: PageDecode.SpeedBinb): ByteArray {
        val source = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw IOException("undecodable page")
        val name = url.substringBefore('?').substringAfterLast('/')
        val layout = SpeedBinb.layout(name, mode.ctbl, mode.ptbl, source.width, source.height)
        if (layout == null || layout.cells.size <= 1) {
            source.recycle()
            return raw // шаблоны непонятны или страница не перемешана: отдаём как есть
        }
        val result = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        for (c in layout.cells) {
            canvas.drawBitmap(
                source,
                Rect(c.srcX, c.srcY, c.srcX + c.width, c.srcY + c.height),
                Rect(c.dstX, c.dstY, c.dstX + c.width, c.dstY + c.height),
                null,
            )
        }
        source.recycle()
        val out = ByteArrayOutputStream(raw.size)
        result.compress(Bitmap.CompressFormat.JPEG, 95, out)
        result.recycle()
        return out.toByteArray()
    }

    private fun cacheName(request: DecodedPageRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${request.url}|${request.decode}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }
}

class DecodedPageKeyer : Keyer<DecodedPageRequest> {
    override fun key(data: DecodedPageRequest, options: Options): String = "decoded:${data.url}|${data.decode}"
}

class DecodedPageFetcher(
    private val data: DecodedPageRequest,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = DecodedPageLoader.file(context, data)
        return SourceFetchResult(
            source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<DecodedPageRequest> {
        override fun create(data: DecodedPageRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            DecodedPageFetcher(data, context.applicationContext)
    }
}
