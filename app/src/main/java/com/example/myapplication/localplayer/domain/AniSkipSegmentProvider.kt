package com.example.myapplication.localplayer.domain

import com.example.myapplication.network.AppJson
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

internal data class AniSkipHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface AniSkipTransport {
    suspend fun get(url: String): AniSkipHttpResponse
}

internal data class AniSkipSelection(
    val segments: List<SkipSegment>,
    val referenceDurationMs: Long?,
)

internal fun interface AniSkipLookup {
    suspend fun fetch(
        anilistId: Int?,
        malId: Int?,
        episodeNumber: Int?,
        durationMs: Long,
    ): AniSkipSelection
}

internal data class AniSkipEpisode(
    val malId: Int,
    val episodeNumber: Int,
)

/**
 * Process-cached AniSkip adapter.
 *
 * `episodeLength=0` removes the server-side duration filter. The current API still exposes only
 * the top-voted record per skip type, so records are checked against actual duration locally.
 */
internal class AniSkipSegmentProvider internal constructor(
    private val transport: AniSkipTransport,
    private val episodeMapper: suspend (Int?, Int?, Int) -> AniSkipEpisode?,
) : AniSkipLookup {

    constructor(
        httpClient: HttpClient,
        franchiseMapper: FranchiseEpisodeMapper,
    ) : this(
        transport = AniSkipTransport { url ->
            val response = httpClient.get(url)
            AniSkipHttpResponse(response.status.value, response.bodyAsText())
        },
        episodeMapper = { anilistId, malId, episode ->
            franchiseMapper.resolve(anilistId, malId, episode)?.let {
                AniSkipEpisode(it.malId, it.episode)
            }
        },
    )

    private val json = AppJson
    private val cacheMutex = Mutex()
    private val processCache = HashMap<EpisodeKey, List<AniSkipRecord>>()
    private val inFlight = HashMap<EpisodeKey, CompletableDeferred<List<AniSkipRecord>?>>()

    override suspend fun fetch(
        anilistId: Int?,
        malId: Int?,
        episodeNumber: Int?,
        durationMs: Long,
    ): AniSkipSelection {
        if (episodeNumber == null || episodeNumber <= 0 || durationMs <= 0L) {
            return AniSkipSelection(emptyList(), null)
        }

        val mapped = try {
            episodeMapper(anilistId, malId, episodeNumber)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val targetMalId = mapped?.malId ?: malId ?: return AniSkipSelection(emptyList(), null)
        val targetEpisode = mapped?.episodeNumber ?: episodeNumber
        val key = EpisodeKey(targetMalId, targetEpisode)
        val records = loadRecords(key) ?: return AniSkipSelection(emptyList(), null)
        return selectCompatible(records, durationMs)
    }

    private suspend fun loadRecords(key: EpisodeKey): List<AniSkipRecord>? {
        val plan = cacheMutex.withLock {
            processCache[key]?.let { return@withLock LoadPlan.Cached(it) }
            inFlight[key]?.let { return@withLock LoadPlan.Await(it) }
            val deferred = CompletableDeferred<List<AniSkipRecord>?>()
            inFlight[key] = deferred
            LoadPlan.Fetch(deferred)
        }
        return when (plan) {
            is LoadPlan.Cached -> plan.records
            is LoadPlan.Await -> plan.deferred.await()
            is LoadPlan.Fetch -> fetchAndPublish(key, plan.deferred)
        }
    }

    private suspend fun fetchAndPublish(
        key: EpisodeKey,
        deferred: CompletableDeferred<List<AniSkipRecord>?>,
    ): List<AniSkipRecord>? {
        var loaded: List<AniSkipRecord>? = null
        try {
            loaded = loadFromNetwork(key)
            return loaded
        } finally {
            // The owner may be cancelled while the shared request is suspended. Cleanup must still
            // release every waiter and remove the in-flight slot so a later call can retry.
            withContext(NonCancellable) {
                cacheMutex.withLock {
                    if (loaded != null) processCache[key] = loaded
                    inFlight.remove(key)
                    deferred.complete(loaded)
                }
            }
        }
    }

    private suspend fun loadFromNetwork(key: EpisodeKey): List<AniSkipRecord>? {
        val response = try {
            transport.get(requestUrl(key))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logWarning(
                "AniSkip network failed for mal=${key.malId} ep=${key.episode}: ${failure.message}",
            )
            return null
        }
        if (response.statusCode != HTTP_OK) {
            logInfo("AniSkip HTTP ${response.statusCode} for mal=${key.malId} ep=${key.episode}")
            return null
        }
        return runCatching { parseRecords(response.body) }
            .onFailure {
                logWarning(
                    "AniSkip malformed response for mal=${key.malId} ep=${key.episode}: ${it.message}",
                )
            }
            .getOrNull()
    }

    private fun requestUrl(key: EpisodeKey): String = buildString {
        append("https://api.aniskip.com/v2/skip-times/")
        append(key.malId).append('/').append(key.episode)
        append("?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap")
        append("&episodeLength=0")
    }

    private fun parseRecords(body: String): List<AniSkipRecord> {
        val root = json.parseToJsonElement(body).jsonObject
        val bodyStatus = root["statusCode"]?.jsonPrimitive?.intOrNull
        require(bodyStatus == HTTP_OK) { "body statusCode=$bodyStatus" }
        require(root["found"]?.jsonPrimitive?.booleanOrNull == true) { "found is not true" }
        val results = root["results"]?.jsonArray ?: error("results missing")
        require(results.isNotEmpty()) { "results empty" }
        return results.map { element ->
            val obj = element.jsonObject
            val interval = obj["interval"]?.jsonObject ?: error("interval missing")
            val start = interval["startTime"]?.jsonPrimitive?.doubleOrNull
                ?: error("startTime missing")
            val end = interval["endTime"]?.jsonPrimitive?.doubleOrNull
                ?: error("endTime missing")
            val episodeLength = obj["episodeLength"]?.jsonPrimitive?.doubleOrNull
                ?: error("episodeLength missing")
            val skipType = obj["skipType"]?.jsonPrimitive?.content ?: error("skipType missing")
            require(skipType in SUPPORTED_SKIP_TYPES) { "unknown skipType=$skipType" }
            require(start >= 0.0 && end > start && episodeLength > 0.0) {
                "invalid interval or episodeLength"
            }
            AniSkipRecord(
                skipType = skipType,
                startMs = (start * 1_000.0).toLong(),
                endMs = (end * 1_000.0).toLong(),
                episodeLengthMs = (episodeLength * 1_000.0).toLong(),
            )
        }
    }

    private fun selectCompatible(
        records: List<AniSkipRecord>,
        durationMs: Long,
    ): AniSkipSelection {
        val compatible = records.filter {
            areSkipDurationsCompatible(durationMs, it.episodeLengthMs)
        }
        val selected = compatible
            .groupBy(AniSkipRecord::skipType)
            .values
            .map { sameType -> sameType.minBy { abs(it.episodeLengthMs - durationMs) } }
            .mapNotNull { record ->
                val end = record.endMs.coerceAtMost(durationMs)
                if (record.startMs >= end) return@mapNotNull null
                SkipSegment(record.startMs, end, record.kind())
            }
            .sortedBy(SkipSegment::startMs)
        val closestDuration = compatible.minByOrNull {
            abs(it.episodeLengthMs - durationMs)
        }?.episodeLengthMs
        return AniSkipSelection(selected, closestDuration)
    }

    private fun AniSkipRecord.kind(): SkipKind = when (skipType) {
        "op", "mixed-op" -> SkipKind.OPENING
        "ed", "mixed-ed" -> SkipKind.ENDING
        "recap" -> SkipKind.RECAP
        else -> error("Unsupported AniSkip type $skipType")
    }

    private data class EpisodeKey(val malId: Int, val episode: Int)

    private data class AniSkipRecord(
        val skipType: String,
        val startMs: Long,
        val endMs: Long,
        val episodeLengthMs: Long,
    )

    private sealed interface LoadPlan {
        data class Cached(val records: List<AniSkipRecord>) : LoadPlan
        data class Await(val deferred: CompletableDeferred<List<AniSkipRecord>?>) : LoadPlan
        data class Fetch(val deferred: CompletableDeferred<List<AniSkipRecord>?>) : LoadPlan
    }

    private fun logInfo(message: String) {
        // android.jar logging stubs throw in local JVM tests; logging must never change fallback.
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private companion object {
        const val TAG = "AniSkip"
        const val HTTP_OK = 200
        val SUPPORTED_SKIP_TYPES = setOf("op", "ed", "mixed-op", "mixed-ed", "recap")
    }
}
