package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.phnem.vetro.network.BuildConfig
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Вид пропускаемого отрезка у внешних баз; приложение сводит их к своему `SkipKind`. */
enum class ExternalSkipKind { OPENING, ENDING, RECAP, PREVIEW, POST_CREDITS }

/**
 * Отрезок из внешней базы. [referenceDurationMs] — длительность серии, для которой отрезок размечен
 * (у IntroDB её нет): по ней отсеиваются чужие версии (TV/BD, другая озвучка).
 */
data class ExternalSkipSegment(
    val kind: ExternalSkipKind,
    val startMs: Long,
    val endMs: Long,
    val referenceDurationMs: Long?,
    /** 0..1: доля согласных разметок; null — источник не сообщает. */
    val confidence: Double?,
    val votes: Int?,
    val source: EnrichmentSource,
)

// ---------- IntroDB ----------

/**
 * IntroDB — пропуск для кино и сериалов по IMDb id (чтение без ключа). Отрезки `intro`, `recap`,
 * `outro`, `post_credits`, у каждого `confidence` и число разметок. Эталонной длительности серии нет —
 * приложение проверяет, что отрезок помещается в текущее видео.
 */
class IntroDbClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    suspend fun episode(imdbId: String, season: Int, episode: Int): LookupResult<List<ExternalSkipSegment>> =
        fetch("imdb_id=$imdbId&season=$season&episode=$episode")

    suspend fun movie(imdbId: String): LookupResult<List<ExternalSkipSegment>> = fetch("imdb_id=$imdbId&is_movie=true")

    private suspend fun fetch(query: String): LookupResult<List<ExternalSkipSegment>> =
        http.text("IntroDB", "https://api.introdb.app/segments?$query", rate)
            .parse { IntroDbParser.segments(it).takeIf(List<ExternalSkipSegment>::isNotEmpty) }
}

internal object IntroDbParser {
    private val KINDS = listOf(
        "intro" to ExternalSkipKind.OPENING,
        "recap" to ExternalSkipKind.RECAP,
        "outro" to ExternalSkipKind.ENDING,
        "post_credits" to ExternalSkipKind.POST_CREDITS,
    )

    fun segments(body: String): List<ExternalSkipSegment> {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        return KINDS.mapNotNull { (field, kind) ->
            val o = root[field] as? JsonObject ?: return@mapNotNull null
            val start = o.long("start_ms") ?: o.seconds("start_sec") ?: return@mapNotNull null
            val end = o.long("end_ms") ?: o.seconds("end_sec") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            ExternalSkipSegment(
                kind = kind,
                startMs = start,
                endMs = end,
                referenceDurationMs = null,
                confidence = (o["confidence"] as? JsonPrimitive)?.doubleOrNull,
                votes = (o["submission_count"] as? JsonPrimitive)?.intOrNull,
                source = EnrichmentSource.INTRODB,
            )
        }
    }

    private fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)?.doubleOrNull?.toLong()
    private fun JsonObject.seconds(name: String): Long? = (get(name) as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
}

// ---------- Anime-Skip ----------

/**
 * Anime-Skip — GraphQL, `X-Client-ID` (общий id из их документации, сильно лимитирован; свой — в
 * `local.properties`). Разметка — точки-метки: отрезок идёт от метки до следующей. У одной серии
 * бывает несколько версий разной длины (`baseDuration`) — версия выбирается по длительности видео.
 */
class AnimeSkipClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val clientId: () -> String = { BuildConfig.ANIME_SKIP_CLIENT_ID },
) {
    /** Все серии шоу по AniList id; одна серия — по номеру (абсолютному или в сезоне). */
    suspend fun episodesByAnilist(anilistId: Int): LookupResult<List<AnimeSkipEpisode>> {
        val id = clientId().takeIf { it.isNotBlank() } ?: return disabled()
        val showId = when (val shows = graphql(id, SHOWS_BY_ANILIST, "s" to anilistId.toString()).parse(AnimeSkipParser::showIds)) {
            is LookupResult.Found -> shows.value.firstOrNull() ?: return LookupResult.NoMatch
            is LookupResult.NoMatch, is LookupResult.NotFoundById -> return LookupResult.NoMatch
            is LookupResult.Failure -> return shows
        }
        return graphql(id, EPISODES_BY_SHOW, "id" to showId).parse(AnimeSkipParser::episodes)
    }

    private suspend fun graphql(clientId: String, query: String, variable: Pair<String, String>): LookupResult<String> =
        http.text("Anime-Skip", "https://api.anime-skip.com/graphql", rate, HttpMethod.Post) {
            header("X-Client-ID", clientId)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("query", query)
                    put("variables", buildJsonObject { put(variable.first, variable.second) })
                }.toString(),
            )
        }

    private companion object {
        const val SHOWS_BY_ANILIST =
            "query(\$s:String!){findShowsByExternalId(service:ANILIST, serviceId:\$s){id name episodeCount}}"
        const val EPISODES_BY_SHOW =
            "query(\$id:ID!){findEpisodesByShowId(showId:\$id){season number absoluteNumber baseDuration timestamps{at type{name}}}}"
    }
}

data class AnimeSkipEpisode(
    val season: Int?,
    val number: Int?,
    val absoluteNumber: Int?,
    val baseDurationMs: Long?,
    val segments: List<ExternalSkipSegment>,
)

internal object AnimeSkipParser {
    /** Метки Anime-Skip → вид отрезка. Canon/Title Card/Filler/Branding — не пропускаем. */
    private val SKIPPABLE = mapOf(
        "Intro" to ExternalSkipKind.OPENING,
        "New Intro" to ExternalSkipKind.OPENING,
        "Mixed Intro" to ExternalSkipKind.OPENING,
        "Recap" to ExternalSkipKind.RECAP,
        "Credits" to ExternalSkipKind.ENDING,
        "New Credits" to ExternalSkipKind.ENDING,
        "Mixed Credits" to ExternalSkipKind.ENDING,
        "Preview" to ExternalSkipKind.PREVIEW,
    )

    fun showIds(body: String): List<String> {
        val data = EnrichmentJson.parseToJsonElement(body).jsonObject["data"] as? JsonObject ?: return emptyList()
        return (data["findShowsByExternalId"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
    }

    fun episodes(body: String): List<AnimeSkipEpisode> {
        val data = EnrichmentJson.parseToJsonElement(body).jsonObject["data"] as? JsonObject ?: return emptyList()
        return (data["findEpisodesByShowId"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val duration = (o["baseDuration"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
            val marks = (o["timestamps"] as? JsonArray).orEmpty().mapNotNull { t ->
                val to = t as? JsonObject ?: return@mapNotNull null
                val at = (to["at"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val type = ((to["type"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: return@mapNotNull null
                (at * 1000).toLong() to type
            }.sortedBy { it.first }
            AnimeSkipEpisode(
                season = o.int("season"),
                number = o.int("number"),
                absoluteNumber = o.int("absoluteNumber"),
                baseDurationMs = duration,
                segments = intervals(marks, duration),
            )
        }
    }

    /** Метка → отрезок до следующей метки (последняя — до конца серии). */
    fun intervals(marks: List<Pair<Long, String>>, durationMs: Long?): List<ExternalSkipSegment> =
        marks.mapIndexedNotNull { i, (at, type) ->
            val kind = SKIPPABLE[type] ?: return@mapIndexedNotNull null
            val end = marks.getOrNull(i + 1)?.first ?: durationMs ?: return@mapIndexedNotNull null
            if (end <= at) return@mapIndexedNotNull null
            ExternalSkipSegment(kind, at, end, durationMs, confidence = null, votes = null, source = EnrichmentSource.ANIME_SKIP)
        }

    /** Номера у Anime-Skip — строки («1», «12.5»); дробные серии (спешлы) не сопоставляем. */
    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.content?.toIntOrNull()
}
