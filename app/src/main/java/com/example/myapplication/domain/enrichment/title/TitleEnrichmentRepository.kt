package com.example.myapplication.domain.enrichment.title

import android.util.Log
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.network.AniListRemoteDataSource
import com.example.myapplication.network.AniListTitleEnrichment
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.ShikimoriRemoteDataSource
import com.example.myapplication.network.retryOn429
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
/** Выходящий сезон франшизы: его id в каталогах (AniList и MAL = Shikimori). */
data class AiringSeasonRef(val anilistId: Int?, val malId: Int?)

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

    /**
     * [refresh] — отсчёт дошёл до нуля: расписание перечитывается мимо свежего кэша.
     * [airing] — выходящий сезон франшизы (из раскладки по сезонам): у записи коллекции id часто
     * указывают на первый сезон, а расписание нужно того, что выходит сейчас.
     */
    suspend fun load(
        anime: Anime,
        language: AppLanguage,
        refresh: Boolean = false,
        airing: AiringSeasonRef? = null,
    ): TitleEnrichment = coroutineScope {
        val ui = if (language == AppLanguage.RU) "ru" else "en"
        val isAnime = anime.mediaType == MediaType.ANIME
        val tmdbKind = when (anime.mediaType) {
            MediaType.MOVIE -> TmdbKind.MOVIE
            MediaType.SERIES, MediaType.ANIME -> TmdbKind.TV
            MediaType.MANGA -> null
        }
        val tmdbD = async { if (tmdbKind != null && anime.tmdbId != null) tmdbBundle(tmdbKind, anime.tmdbId, language, refresh) else null }
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val onFailure = { failed.set(true) }
        val aniD = async { if (isAnime) anime.anilistId?.let { aniListEnrichment(it, onFailure) } else null }
        val shikiD = async { if (isAnime) anime.shikimoriId?.let { shikimoriEnrichment(it, onFailure) } else null }
        // Эфир выходящего сезона (AniList): точное время серии в Японии. Для франшизы — узел
        // выходящего сезона, иначе сама запись.
        val airingD = async {
            if (!isAnime) return@async null
            val id = airing?.anilistId ?: anime.anilistId ?: return@async null
            if (id == anime.anilistId) aniD.await() else aniListEnrichment(id, onFailure)
        }
        // RU-трек аниме — расписание озвучки AniLibria. Shikimori id совпадает с MAL id, поэтому
        // сверяем и id записи, и id выходящего сезона.
        val dubD = async {
            if (!isAnime || language != AppLanguage.RU) return@async null
            val ids = setOfNotNull(anime.shikimoriId, anime.malId, airing?.malId)
            if (ids.isEmpty()) return@async null
            aniLibria.week(refresh).valueOrNull()?.firstOrNull { item ->
                (item.shikimoriId != null && item.shikimoriId in ids) || (item.malId != null && item.malId in ids)
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
        val trailer = pickTrailer(anime, language, aniR, shikiR, tmdbR, ui)
        trailer?.let { provenance["trailer"] = it.source }
        val ratings = omdbD.await()
        if (ratings != null) provenance["ratings"] = EnrichmentSource.OMDB
        // В порядке точности: озвучка (RU), TVmaze и AniList — точное время, TMDb — только дата.
        val schedules = listOfNotNull(
            dubD.await()?.let { ReleaseCountdownRules.fromAniLibria(it, now.atZone(zone()).toLocalDate(), zone()) },
            tvMazeD.await()?.let(ReleaseCountdownRules::fromTvMaze),
            airingD.await()?.let { ReleaseCountdownRules.fromAniList(it, now) },
            tmdbR?.let(ReleaseCountdownRules::fromTmdb),
        )
        val next = ReleaseCountdownRules.select(language, schedules, now, zone())
        if (next == null && schedules.isNotEmpty()) {
            Log.d(TAG, "${anime.id}: no countdown from ${schedules.joinToString { "${it.source}/${it.track} next=${it.next} prev=${it.previous} finished=${it.finished}" }}")
        }
        if (isAnime && schedules.none { it.source == EnrichmentSource.ANILIST }) {
            Log.d(TAG, "${anime.id}: no AniList airing schedule (airing=${airing}, anilistId=${anime.anilistId})")
        }
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
            incomplete = failed.get(),
        )
    }

    /**
     * Трейлер. Сначала — поиск YouTube по запросу на языке интерфейса («Наруто 1 сезон трейлер на
     * русском»): официальный трейлер AniList — это японский PV случайного сезона, а пользователю
     * нужен ролик на его языке. Не нашлось (или нет ключа) — официальный трейлер AniList, PV
     * Shikimori, ролики TMDb; они проверяются одним `videos.list` (1 единица квоты): удалённый или
     * закрытый ролик не показываем.
     */
    private suspend fun pickTrailer(
        anime: Anime,
        language: AppLanguage,
        aniR: AniListTitleEnrichment?,
        shikiR: ShikimoriEnrichment?,
        tmdbR: TmdbEnrichment?,
        ui: String,
    ): VideoClip? {
        searchTrailer(anime, language)?.let { return it }
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

    private suspend fun searchTrailer(anime: Anime, language: AppLanguage): VideoClip? {
        if (!youTube.isConfigured || anime.mediaType == MediaType.MANGA) return null
        val query = TitleEnrichmentRules.trailerQuery(anime, language) ?: return null
        val ui = if (language == AppLanguage.RU) "ru" else "en"
        val found = youTube.search(query, maxResults = 8, relevanceLanguage = ui).valueOrNull().orEmpty()
        val best = TitleEnrichmentRules.pickSearchedTrailer(found, language) ?: return null
        return VideoClip("YouTube", best.id, best.title, "Trailer", official = false, language = ui, source = EnrichmentSource.YOUTUBE)
    }

    private suspend fun tmdbBundle(kind: TmdbKind, id: Int, language: AppLanguage, refresh: Boolean): TmdbEnrichment? =
        when (val r = tmdb.bundle(kind, id, language, refresh)) {
            is LookupResult.Found -> r.value
            // У аниме-фильма tmdbId указывает на /movie, а тип записи — ANIME: пробуем второй вид.
            is LookupResult.NotFoundById -> if (kind == TmdbKind.TV) tmdb.bundle(TmdbKind.MOVIE, id, language, refresh).valueOrNull() else null
            else -> null
        }

    // Провал запроса (чаще всего 429: сразу после старта AniList нагружает проверка серий) не
    // запоминается — getOrThrow уходит мимо memo, и следующее открытие карточки спросит снова. Иначе
    // пустой ответ жил бы полчаса, а с ним пропадали трейлер и отсчёт до серии.
    private suspend fun aniListEnrichment(id: Int, onFailure: () -> Unit): AniListTitleEnrichment? =
        memo(aniListMemo, id, onFailure) { retryOn429(maxAttempts = 3, baseDelayMs = 1_500L) { aniList.titleEnrichment(id) }.getOrThrow() }

    private suspend fun shikimoriEnrichment(id: Int, onFailure: () -> Unit): ShikimoriEnrichment? =
        memo(shikimoriMemo, id, onFailure) { shikimori.animeEnrichment(id).getOrThrow() }

    private suspend fun <T> memo(
        map: ConcurrentHashMap<Int, Memo<T>>,
        id: Int,
        onFailure: () -> Unit,
        load: suspend () -> T?,
    ): T? {
        map[id]?.takeIf { nowMs() - it.at < MEMO_TTL_MS }?.let { return it.value }
        return try {
            load().also { map[id] = Memo(it, nowMs()) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "lookup $id failed: ${e.javaClass.simpleName}: ${e.message}")
            onFailure()
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
