package com.example.myapplication.audiobooks.catalog

import com.example.myapplication.media.source.await
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

object BookCatalogConfig {
    const val API_V1 = "https://api.vetrostudio.bid/v1"
    const val COVERS = "https://api.vetrostudio.bid/covers"

    /** Обложки каталога — AVIF: их читает системный декодер Android 12+ (до него — обложку ищут другие источники). */
    val coversSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/** Серверный поиск по всему каталогу (`GET /v1/search`, D1 + FTS5). Сбой сети — null, не исключение. */
class BookCatalogApi(
    private val http: OkHttpClient,
    private val apiV1: String = BookCatalogConfig.API_V1,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int = 20): List<CatalogBook>? = withContext(Dispatchers.IO) {
        try {
            val url = "$apiV1/search".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", query)?.addQueryParameter("limit", limit.toString())?.build() ?: return@withContext null
            http.newCall(Request.Builder().url(url).build()).await().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                json.parseToJsonElement(resp.body.string()).jsonObject["items"]?.jsonArray?.mapNotNull { parse(it.jsonObject) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Карточка одной книги (`GET /v1/books/{id}`). null — сеть, 404 или у книги нет подробностей; не исключение. */
    suspend fun details(vid: String): CatalogDetails? = withContext(Dispatchers.IO) {
        try {
            val url = "$apiV1/books/$vid".toHttpUrlOrNull() ?: return@withContext null
            http.newCall(Request.Builder().url(url).build()).await().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                parseCatalogDetails(vid, json.parseToJsonElement(resp.body.string()).jsonObject)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun parse(o: JsonObject): CatalogBook? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        return CatalogBook(
            id = id, ru = s("ru") ?: s("en") ?: return null, en = s("en")?.takeIf { s("ru") != null },
            ruAuthor = s("ra") ?: s("ea") ?: "", enAuthor = s("ea")?.takeIf { s("ra") != null },
            year = o["y"]?.jsonPrimitive?.intOrNull, flags = o["f"]?.jsonPrimitive?.intOrNull ?: 0, coverRev = o["cv"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}

/**
 * Каталог книг как первый источник поиска: сначала телефонный индекс (мгновенно, без сети), и только если
 * в нём мало хороших совпадений — сервер. Аудио-источники подключаются позже, по найденному названию.
 *
 * VETRO-TAIL[book-catalog-rerank]: сюда встаёт модель переранжирования (TitleMatch/MicroML) поверх [CatalogRanker].
 */
class BookCatalog(
    private val store: BookIndexStore,
    private val api: BookCatalogApi,
    private val scope: CoroutineScope,
) {
    private val warmed = AtomicBoolean(false)

    /** Обновление индекса в фоне: один раз за запуск приложения, дальше решает [BookIndexStore.CHECK_INTERVAL_MS]. */
    fun warmUp() {
        if (warmed.compareAndSet(false, true)) scope.launch { store.refresh() }
    }

    suspend fun search(query: String, limit: Int = 20): List<CatalogBook> {
        warmUp()
        // SQL и ранжирование — не на потоке вызывающего: поиск зовут из главной.
        val local = store.query()?.let { index ->
            withContext(Dispatchers.IO) { runCatching { BookIndexSearch(index).search(query, MAX_LOCAL) }.getOrNull() }
        }.orEmpty()
        if (strong(query, local) >= ENOUGH_LOCAL) return local.take(limit)
        val remote = withTimeoutOrNull(API_TIMEOUT_MS) { api.search(query, limit) }.orEmpty()
        if (remote.isEmpty()) return local.take(limit)
        // Локальные записи первыми: в них есть другие названия, которых нет в ответе сервера.
        val merged = LinkedHashMap<Int, CatalogBook>()
        local.forEach { merged[it.id] = it }
        remote.forEach { merged.putIfAbsent(it.id, it) }
        return CatalogRanker.rank(query, merged.values.toList()).take(limit)
    }

    /**
     * Подробности книги (описание, жанры, серия, оригинал…) по id каталога. Ответ сервера кэшируется на сутки, а здесь —
     * в памяти на время сессии: карточка открывается и из поиска, и со страницы книги. Нет ответа за [API_TIMEOUT_MS] — null.
     */
    suspend fun details(vid: String): CatalogDetails? {
        synchronized(detailsCache) { detailsCache[vid] }?.let { return it }
        val fresh = withTimeoutOrNull(API_TIMEOUT_MS) { api.details(vid) } ?: return null
        synchronized(detailsCache) { detailsCache[vid] = fresh }
        return fresh
    }

    private val detailsCache = object : LinkedHashMap<String, CatalogDetails>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogDetails>?) = size > DETAILS_REMEMBERED
    }

    fun coverUrl(book: CatalogBook, large: Boolean = false): String? =
        if (book.hasCover && BookCatalogConfig.coversSupported) {
            "${BookCatalogConfig.COVERS}/${if (large) "large" else "small"}/${book.vid}.avif?v=${book.coverRev}"
        } else {
            null
        }

    private fun strong(query: String, books: List<CatalogBook>): Int {
        val q = BookNorm.tokens(query).filter { it.length > 1 }
        if (q.isEmpty()) return 0
        val qNorm = q.joinToString(" ")
        return books.count { CatalogRanker.score(q, qNorm, it) >= STRONG_SCORE }
    }

    private companion object {
        const val MAX_LOCAL = 40
        /** Столько хороших локальных совпадений достаточно, чтобы сервер не спрашивать. */
        const val ENOUGH_LOCAL = 3
        /** Все слова запроса найдены (покрытие 200 + хотя бы слово в названии 100). */
        const val STRONG_SCORE = 300
        const val API_TIMEOUT_MS = 4_000L
        const val DETAILS_REMEMBERED = 100
    }
}
