package com.example.myapplication.mediacatalog

import com.example.myapplication.audiobooks.catalog.BookCatalogConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Запись каталога Vetro (аниме, фильм или сериал), как её отдаёт api.vetrostudio.bid. */
data class VetroHit(
    /** Vetro id: `A00000050` (аниме), `M…` (фильм), `S…` (сериал). */
    val id: String,
    /** `anime` | `movie` | `tv`. */
    val kind: String,
    /** Названия на всех языках: русское, английское, ромадзи, оригинальное. */
    val titles: List<String>,
    val year: Int?,
    /** Бит 1 — у тайтла есть обложка в каталоге. */
    val flags: Int,
    val coverRevision: Int,
    /** Id другого сервиса, по которому запись найдена (`anilist:16498`); null — найдена иначе. */
    val via: String?,
    /** Id тайтла в других базах: `anilist`, `mal`, `shikimori`, `tmdb`, `imdb`, … */
    val ext: Map<String, String>,
) {
    val hasCover: Boolean get() = flags and 1 != 0
}

/**
 * Каталог Vetro для привязки тайтлов коллекции и для отсева рекомендаций. Каждый метод возвращает [Result]:
 * сбой сети — отказ, а не «ничего не найдено», иначе тайтл навсегда записался бы как «в каталоге нет».
 */
interface VetroCatalog {
    /** Тайтлы каталога по id в других базах (`anilist:16498`, `tmdb:13916`); [kind] — `anime` | `movie` | `tv` или null. До 40 ключей. */
    suspend fun byExternalIds(keys: List<String>, kind: String?): Result<List<VetroHit>>

    /** Поиск по названию внутри одной базы. */
    suspend fun byTitle(query: String, kind: String, limit: Int = 5): Result<List<VetroHit>>

    /** Строки каталога по Vetro id (до 40). */
    suspend fun byIds(ids: List<String>): Result<List<VetroHit>>

    /** Обложка тайтла (AVIF); null, если обложек нет или устройство их не читает. */
    suspend fun downloadCover(hit: VetroHit): Result<ByteArray>?
}

class HttpVetroCatalog(
    private val http: OkHttpClient,
    private val apiV1: String = BookCatalogConfig.API_V1,
    private val covers: String = BookCatalogConfig.COVERS,
    private val coversSupported: () -> Boolean = { BookCatalogConfig.coversSupported },
) : VetroCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { http.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build() }

    override suspend fun byExternalIds(keys: List<String>, kind: String?): Result<List<VetroHit>> =
        get("/media/ext", "ids" to keys.take(MAX_IDS).joinToString(","), "kind" to kind)

    override suspend fun byTitle(query: String, kind: String, limit: Int): Result<List<VetroHit>> =
        get("/find", "q" to query, "kind" to kind, "limit" to limit.toString())

    override suspend fun byIds(ids: List<String>): Result<List<VetroHit>> =
        get("/media", "ids" to ids.take(MAX_IDS).joinToString(","))

    override suspend fun downloadCover(hit: VetroHit): Result<ByteArray>? {
        if (!hit.hasCover || !coversSupported()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$covers/media/large/${hit.id}.avif?v=${hit.coverRevision}").build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("cover ${hit.id}: HTTP ${resp.code}")
                    resp.body.bytes()
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun get(path: String, vararg params: Pair<String, String?>): Result<List<VetroHit>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$apiV1$path".toHttpUrlOrNull()?.newBuilder()?.apply {
                params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            }?.build() ?: error("bad url")
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                parse(resp.body.string())
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    internal fun parse(body: String): List<VetroHit> =
        json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { hit(it.jsonObject) }

    private fun hit(o: JsonObject): VetroHit? {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val id = s("id") ?: return null
        val kind = s("kind") ?: when (s("t")) {
            "anime" -> "anime"
            "movie" -> "movie"
            "tv_series" -> "tv"
            else -> return null
        }
        val ext = o["ext"]?.jsonObject?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it } }?.toMap().orEmpty()
        return VetroHit(
            id = id,
            kind = kind,
            titles = listOfNotNull(s("ru"), s("en"), s("ro"), s("orig")).distinct(),
            year = o["y"]?.jsonPrimitive?.intOrNull,
            flags = o["f"]?.jsonPrimitive?.intOrNull ?: 0,
            coverRevision = o["cv"]?.jsonPrimitive?.intOrNull ?: 0,
            via = s("via"),
            ext = ext,
        )
    }

    companion object {
        const val MAX_IDS = 40
        private const val TIMEOUT_SECONDS = 10L
    }
}
