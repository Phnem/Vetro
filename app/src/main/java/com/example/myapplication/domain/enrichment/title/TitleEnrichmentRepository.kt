package com.example.myapplication.domain.enrichment.title

import android.util.Log
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.network.AniListRemoteDataSource
import com.example.myapplication.network.AniListTitleEnrichment
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.ShikimoriRemoteDataSource
import com.example.myapplication.network.enrichment.AniLibriaScheduleClient
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.FanartClient
import com.example.myapplication.network.enrichment.OmdbClient
import com.example.myapplication.network.enrichment.ShikimoriEnrichment
import com.example.myapplication.network.enrichment.TmdbEnrichment
import com.example.myapplication.network.enrichment.TmdbEnrichmentClient
import com.example.myapplication.network.enrichment.TmdbKind
import com.example.myapplication.network.enrichment.TvMazeClient
import com.example.myapplication.network.enrichment.TvMazeShow
import com.example.myapplication.network.enrichment.VideoClip
import com.example.myapplication.network.enrichment.YouTubeClient
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Сборка обогащения тайтла для Details (.scratch/sources-expansion/ARCHITECTURE.md, «Порядок
 * запросов»). Параллельно — то, что нужно экрану сразу: TMDb (картинки, ролики, внешние id одним
 * запросом), AniList и Shikimori для аниме, расписание русской озвучки AniLibria (аниме, русский
 * интерфейс). После внешних id — OMDb, TVmaze (сериалы) и Fanart.tv (только если у TMDb нет
 * логотипа). Отсчёт до серии — по треку языка интерфейса ([ReleaseCountdownRules]). Ответы кэшируются
 * слоем [com.example.myapplication.network.enrichment.EnrichmentHttp]; AniList/Shikimori — здесь, в памяти.
 */
class TitleEnrichmentRepository(
    private val tmdb: TmdbEnrichmentClient,
    private val aniList: AniListRemoteDataSource,
    private val shikimori: ShikimoriRemoteDataSource,
    private val tvMaze: TvMazeClient,
    private val omdb: OmdbClient,
    private val fanart: FanartClient,
    private val youTube: YouTubeClient,
    private val aniLibria: AniLibriaScheduleClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private data class Memo<T>(val value: T?, val at: Long)

    private val aniListMemo = ConcurrentHashMap<Int, Memo<AniListTitleEnrichment>>()
    private val shikimoriMemo = ConcurrentHashMap<Int, Memo<ShikimoriEnrichment>>()

    /** [refresh] — отсчёт дошёл до нуля: расписание перечитывается мимо свежего кэша. */
    suspend fun load(anime: Anime, language: AppLanguage, refresh: Boolean = false): TitleEnrichment = coroutineScope {
        val ui = if (language == AppLanguage.RU) "ru" else "en"
        val isAnime = anime.mediaType == MediaType.ANIME
        val tmdbKind = when (anime.mediaType) {
            MediaType.MOVIE -> TmdbKind.MOVIE
            MediaType.SERIES, MediaType.ANIME -> TmdbKind.TV
            MediaType.MANGA -> null
        }
        val tmdbD = async { if (tmdbKind != null && anime.tmdbId != null) tmdbBundle(tmdbKind, anime.tmdbId, language, refresh) else null }
        val aniD = async { if (isAnime) anime.anilistId?.let { aniListEnrichment(it) } else null }
        val shikiD = async { if (isAnime) anime.shikimoriId?.let { shikimoriEnrichment(it) } else null }
        // RU-трек аниме — расписание озвучки; для английского интерфейса у аниме трека нет.
        val dubD = async {
            if (!isAnime || language != AppLanguage.RU || (anime.shikimoriId == null && anime.malId == null)) return@async null
            aniLibria.week(refresh).valueOrNull()?.firstOrNull { item ->
                (anime.shikimoriId != null && item.shikimoriId == anime.shikimoriId) ||
                    (anime.malId != null && item.malId == anime.malId)
            }
        }

        val tmdbR = tmdbD.await()
        val aniR = aniD.await()
        val shikiR = shikiD.await()
        val imdb = anime.imdbId ?: tmdbR?.imdbId
        val tvdb = tmdbR?.tvdbId
        val now = Instant.ofEpochMilli(nowMs())

        val omdbD = async {
            if (anime.mediaType == MediaType.MOVIE || anime.mediaType == MediaType.SERIES) imdb?.let { omdb.byImdb(it).valueOrNull() } else null
        }
        // TVmaze — точное время серии для сериалов; у аниме эфир японский и трек языка не даёт.
        val tvMazeD = async {
            if (anime.mediaType == MediaType.SERIES && (imdb != null || tvdb != null)) tvMaze.show(imdb, tvdb, refresh).valueOrNull() else null
        }
        val tmdbLogo = TitleEnrichmentRules.pickLogo(tmdbR?.logos.orEmpty(), ui)
        val fanartD = async {
            if (tmdbLogo != null) return@async null
            when {
                tmdbKind == TmdbKind.MOVIE && anime.tmdbId != null -> fanart.movie(anime.tmdbId.toString()).valueOrNull()
                tvdb != null -> fanart.tv(tvdb).valueOrNull()
                else -> null
            }
        }

        val provenance = mutableMapOf<String, EnrichmentSource>()
        val logo = tmdbLogo ?: TitleEnrichmentRules.pickLogo(fanartD.await()?.logos.orEmpty(), ui)
        logo?.let { provenance["logo"] = it.source }
        val backdrop = TitleEnrichmentRules.pickBackdrop(tmdbR?.backdrops.orEmpty(), ui)
            ?: TitleEnrichmentRules.pickBackdrop(fanartD.await()?.backgrounds.orEmpty(), ui)
        backdrop?.let { provenance["backdrop"] = it.source }
        val trailer = pickTrailer(aniR, shikiR, tmdbR, ui)
        trailer?.let { provenance["trailer"] = it.source }
        val ratings = omdbD.await()
        if (ratings != null) provenance["ratings"] = EnrichmentSource.OMDB
        val schedules = listOfNotNull(
            dubD.await()?.let { ReleaseCountdownRules.fromAniLibria(it, now.atZone(zone()).toLocalDate(), zone()) },
            tvMazeD.await()?.let(ReleaseCountdownRules::fromTvMaze),
            tmdbR?.let(ReleaseCountdownRules::fromTmdb),
        )
        val next = ReleaseCountdownRules.select(language, schedules, now, zone())
        next?.let { provenance["nextRelease"] = it.source }
        if (provenance.isNotEmpty()) Log.d(TAG, "${anime.id}: ${provenance.entries.joinToString { "${it.key}←${it.value}" }}")

        TitleEnrichment(
            logo = logo,
            backdrop = backdrop,
            trailer = trailer,
            ratings = ratings,
            nextRelease = next,
            russianDubs = shikiR?.fandubbers.orEmpty(),
            provenance = provenance,
        )
    }

    /**
     * Трейлер: для аниме — официальный трейлер AniList, затем PV Shikimori; затем ролики TMDb. Если есть
     * ключ YouTube, кандидаты проверяются одним `videos.list` (1 единица квоты): удалённый или закрытый
     * ролик не показываем.
     */
    private suspend fun pickTrailer(
        aniR: AniListTitleEnrichment?,
        shikiR: ShikimoriEnrichment?,
        tmdbR: TmdbEnrichment?,
        ui: String,
    ): VideoClip? {
        val candidates = buildList {
            if (aniR?.trailerSite.equals("youtube", true) && !aniR?.trailerId.isNullOrBlank()) {
                add(VideoClip("YouTube", aniR!!.trailerId!!, "Trailer", "Trailer", official = true, language = null, source = EnrichmentSource.ANILIST))
            }
            shikiR?.videos.orEmpty().filter { it.kind == "pv" }.mapNotNull { v ->
                youtubeId(v.url)?.let { VideoClip("YouTube", it, v.name.orEmpty(), "Trailer", official = false, language = "ja", source = EnrichmentSource.SHIKIMORI) }
            }.let(::addAll)
            TitleEnrichmentRules.pickTrailer(tmdbR?.videos.orEmpty(), ui)?.let(::add)
        }
        if (candidates.isEmpty()) return null
        if (!youTube.isConfigured) return candidates.first()
        val alive = youTube.videos(candidates.map { it.key }.distinct()).valueOrNull()
            ?.filter { it.public != false && it.embeddable != false }?.map { it.id }?.toSet()
            ?: return candidates.first()
        return candidates.firstOrNull { it.key in alive }
    }

    private suspend fun tmdbBundle(kind: TmdbKind, id: Int, language: AppLanguage, refresh: Boolean): TmdbEnrichment? =
        when (val r = tmdb.bundle(kind, id, language, refresh)) {
            is LookupResult.Found -> r.value
            // У аниме-фильма tmdbId указывает на /movie, а тип записи — ANIME: пробуем второй вид.
            is LookupResult.NotFoundById -> if (kind == TmdbKind.TV) tmdb.bundle(TmdbKind.MOVIE, id, language, refresh).valueOrNull() else null
            else -> null
        }

    private suspend fun aniListEnrichment(id: Int): AniListTitleEnrichment? =
        memo(aniListMemo, id) { aniList.titleEnrichment(id).getOrNull() }

    private suspend fun shikimoriEnrichment(id: Int): ShikimoriEnrichment? =
        memo(shikimoriMemo, id) { shikimori.animeEnrichment(id).getOrNull() }

    private suspend fun <T> memo(map: ConcurrentHashMap<Int, Memo<T>>, id: Int, load: suspend () -> T?): T? {
        map[id]?.takeIf { nowMs() - it.at < MEMO_TTL_MS }?.let { return it.value }
        return try {
            load().also { map[id] = Memo(it, nowMs()) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun <T> LookupResult<T>.valueOrNull(): T? = (this as? LookupResult.Found)?.value

    companion object {
        private const val TAG = "TitleEnrichment"
        private const val MEMO_TTL_MS = 30 * 60 * 1000L

        /** `https://youtube.com/watch?v=ID`, `https://youtu.be/ID`, `…/embed/ID` → ID. */
        fun youtubeId(url: String): String? =
            Regex("""(?:v=|youtu\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
    }
}
