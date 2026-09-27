package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import java.time.DayOfWeek
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Расписание русской озвучки AniLibria (`/anime/schedule/week`, без ключа): все выходящие релизы
 * одним запросом. Это RU-трек выхода аниме: день недели выпуска озвучки, номер следующей серии и
 * когда вышла прошлая. Времени выпуска в расписании нет — только день.
 */
class AniLibriaScheduleClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    suspend fun week(refresh: Boolean = false): LookupResult<List<AniLibriaScheduleItem>> =
        http.text("AniLibria", "$BASE/anime/schedule/week", rate, refresh = refresh,
            policy = CachePolicy("anilibria:schedule:week", 3 * CacheTtl.HOUR))
            .parse { AniLibriaScheduleParser.week(it).takeIf(List<AniLibriaScheduleItem>::isNotEmpty) }

    private companion object {
        const val BASE = "https://anilibria.top/api/v1"
    }
}

data class AniLibriaScheduleItem(
    val releaseId: Int,
    val shikimoriId: Int?,
    val malId: Int?,
    val ongoing: Boolean,
    /** День выпуска озвучки; null — не указан. */
    val publishDay: DayOfWeek?,
    val nextEpisode: Int?,
    val lastEpisode: Int?,
    /**
     * Когда вышла прошлая серия озвучки: `fresh_at` релиза (момент добавления новой серии), иначе
     * `updated_at` серии — он меняется и при правке старой серии, поэтому только запасной.
     */
    val lastReleasedAt: Instant?,
)

internal object AniLibriaScheduleParser {
    @Serializable
    private data class ItemDto(
        val release: ReleaseDto,
        val published_release_episode: EpisodeDto? = null,
        val next_release_episode_number: Int? = null,
    )

    @Serializable
    private data class ReleaseDto(
        val id: Int,
        val shikimori: RefDto? = null,
        val mal: RefDto? = null,
        val fresh_at: String? = null,
        val is_ongoing: Boolean = false,
        val publish_day: DayDto? = null,
    )

    @Serializable
    private data class RefDto(val id: Int? = null)

    @Serializable
    private data class DayDto(val value: Int? = null)

    @Serializable
    private data class EpisodeDto(val ordinal: Double? = null, val updated_at: String? = null)

    fun week(body: String): List<AniLibriaScheduleItem> =
        EnrichmentJson.decodeFromString(ListSerializer(ItemDto.serializer()), body).map { d ->
            AniLibriaScheduleItem(
                releaseId = d.release.id,
                shikimoriId = d.release.shikimori?.id,
                malId = d.release.mal?.id,
                ongoing = d.release.is_ongoing,
                // 1 — понедельник … 7 — воскресенье, как в ISO.
                publishDay = d.release.publish_day?.value?.takeIf { it in 1..7 }?.let(DayOfWeek::of),
                nextEpisode = d.next_release_episode_number,
                lastEpisode = d.published_release_episode?.ordinal?.toInt(),
                lastReleasedAt = parseInstant(d.release.fresh_at) ?: parseInstant(d.published_release_episode?.updated_at),
            )
        }
}
