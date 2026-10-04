package com.example.myapplication.media.source.movieseries

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.net.URLEncoder
import java.time.Year
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Фильмы из коллекции Internet Archive «Feature Films» — только те, что законно смотреть: с отметкой
 * общественного достояния или Creative Commons, либо вышедшие так давно, что в США они уже в
 * общественном достоянии. Остальные загрузки коллекции (там бывают и чужие фильмы) не отдаём.
 *
 * Тайтл находится по IMDb-id (`external-identifier: urn:imdb:tt…`, есть у части фильмов), а без него —
 * только по точному совпадению английского названия.
 */
class InternetArchiveFilmsProvider(
    private val client: HttpClient,
    private val currentYear: () -> Int = { Year.now().value },
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("internet-archive-films")
    override val displayName = "Internet Archive"
    override val capabilities = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.EN,
        ProviderCapability.DIRECT,
        ProviderCapability.DOWNLOAD,
        ProviderCapability.IMDB_ID,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolveTyped(displayName) {
        if (request.mediaType != MediaType.MOVIE) return@resolveTyped ProviderResolution.Unsupported
        try {
            val imdb = request.imdbId?.trim()?.takeIf { it.startsWith("tt") }
            val byImdb = imdb?.let { search("external-identifier:\"urn:imdb:$it\"") }.orEmpty()
            val (candidates, accuracy) = if (byImdb.isNotEmpty()) {
                byImdb to MatchAccuracy.IMDB_ID
            } else {
                val title = (request.anime.titleEn ?: request.anime.title).trim()
                val words = title.replace(Regex("""[()":\\\[\]{}]"""), " ").trim()
                if (words.isEmpty()) return@resolveTyped ProviderResolution.NotFound
                search("title:($words)").filter { InternetArchiveFilms.sameTitle(it.title, title) } to MatchAccuracy.TITLE_ONLY
            }
            // Цельный файл лучше фильма, порезанного на части: смотрим до трёх законных кандидатов.
            var fallback: List<VetroVideo>? = null
            for (item in candidates.filter { InternetArchiveFilms.isFreeToWatch(it, currentYear()) }.take(3)) {
                val videos = files(item.identifier)
                if (videos.size == 1) return@resolveTyped found(videos, accuracy)
                if (fallback == null && videos.isNotEmpty()) fallback = videos
            }
            fallback?.let { found(it, accuracy) } ?: ProviderResolution.NotFound
        } catch (e: IOException) {
            ProviderResolution.TemporaryError(e.javaClass.simpleName)
        }
    }

    private fun found(videos: List<VetroVideo>, accuracy: MatchAccuracy) =
        ProviderResolution.Found(listOf(VetroHoster(name = displayName, videos = videos)), accuracy = accuracy)

    private suspend fun search(query: String): List<InternetArchiveFilms.Item> {
        val response = client.get("$BASE/advancedsearch.php") {
            parameter("q", "$query AND collection:(feature_films) AND mediatype:(movies)")
            FIELDS.forEach { parameter("fl[]", it) }
            parameter("rows", "10")
            parameter("sort[]", "downloads desc")
            parameter("output", "json")
        }
        return InternetArchiveFilms.parseSearch(response.okBody("search"))
    }

    private suspend fun files(identifier: String): List<VetroVideo> {
        val response = client.get("$BASE/metadata/$identifier")
        return InternetArchiveFilms.parseFiles(response.okBody("metadata"), identifier, displayName)
    }

    private suspend fun HttpResponse.okBody(label: String): String {
        requireProviderSuccess(status.value, "$displayName $label")
        return bodyAsText()
    }

    private companion object {
        const val BASE = "https://archive.org"
        val FIELDS = listOf("identifier", "title", "year", "licenseurl")
    }
}

internal object InternetArchiveFilms {
    data class Item(val identifier: String, val title: String, val year: Int?, val license: String?)

    private val json = Json { ignoreUnknownKeys = true }

    /** Сколько лет после выхода фильм в США остаётся под охраной (95 лет для работ 1927–1977). */
    private const val US_TERM_YEARS = 95

    fun parseSearch(body: String): List<Item> {
        val docs = runCatching {
            ((json.parseToJsonElement(body) as JsonObject)["response"] as JsonObject)["docs"] as JsonArray
        }.getOrElse { throw IllegalArgumentException("search shape", it) }
        return docs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Item(
                identifier = o.text("identifier") ?: return@mapNotNull null,
                title = o.text("title") ?: return@mapNotNull null,
                year = o.text("year")?.take(4)?.toIntOrNull(),
                license = o.text("licenseurl"),
            )
        }
    }

    /** Законно смотреть: отметка PD/CC или выход раньше конца срока охраны в США. */
    fun isFreeToWatch(item: Item, currentYear: Int): Boolean {
        val license = item.license?.lowercase().orEmpty()
        if ("publicdomain" in license || "creativecommons.org/licenses" in license) return true
        val year = item.year ?: return false
        return year < currentYear - US_TERM_YEARS
    }

    fun sameTitle(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** MP4-форматы IA по убыванию качества: оригинал h.264, затем деривативы. */
    private val FORMATS = listOf("h.264 hd", "h.264", "mpeg4", "512kb mpeg4")

    /**
     * Видео единицы в одном, лучшем из доступных MP4-форматов. Один файл — один фильм; несколько —
     * фильм порезан на части, они идут по порядку имён с подписью «Часть N из M».
     */
    fun parseFiles(body: String, identifier: String, sourceName: String): List<VetroVideo> {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }
            .getOrElse { throw IllegalArgumentException("metadata shape", it) }
        val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.text("name").orEmpty().lowercase().endsWith(".mp4") }
        val format = FORMATS.firstOrNull { wanted -> files.any { it.text("format").orEmpty().lowercase() == wanted } }
            ?: return emptyList()
        val chosen = files.filter { it.text("format").orEmpty().lowercase() == format }.sortedBy { it.text("name") }
        return chosen.mapIndexedNotNull { i, f ->
            val name = f.text("name") ?: return@mapIndexedNotNull null
            val height = f.text("height")?.toIntOrNull()
            val part = if (chosen.size > 1) "Part ${i + 1}/${chosen.size}" else null
            VetroVideo(
                url = "https://archive.org/download/$identifier/" +
                    name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") },
                label = listOfNotNull(part, height?.let { "${it}p" }).joinToString(" · ").ifEmpty { "MP4" },
                sourceName = sourceName,
                resolution = height,
                downloadAllowed = true,
            )
        }
    }

    private fun JsonObject.text(name: String): String? = when (val e = get(name)) {
        is JsonPrimitive -> e.content.trim().takeIf { it.isNotEmpty() }
        is JsonArray -> (e.firstOrNull() as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }
}
