package com.example.myapplication.domain.seasons

import android.util.Log
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.AnimeHeavenSource
import com.example.myapplication.media.source.JutSuSource
import com.example.myapplication.media.source.KodikDirectSearch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Сезон, найденный у источника просмотра: номер и сколько серий там реально лежит. */
data class DiscoveredSeason(
    val seasonNumber: Int,
    val episodes: Int,
    /** Имя источника — попадёт в [SeasonInfo.source] и в подпись сезона. */
    val source: String,
)

/**
 * Enriches a trusted base list with what streaming sources currently expose.
 *
 * The caller chooses the base: the cached list for the initial discovery, or a freshly resolved
 * catalogue list for a full refresh. Starting a refresh from the cache would preserve stale rows.
 */
internal fun mergeSeasonDiscovery(
    known: List<SeasonInfo>,
    discovered: List<DiscoveredSeason>,
): List<SeasonInfo> {
    // Спецвыпуски (OVA, фильмы) в шкале источников просмотра не существуют: те нумеруют только
    // сезоны. Сливаем сезонную часть, а спецвыпуски возвращаем в хвост нетронутыми — их номера
    // лежат в своём диапазоне ([SPECIAL_SEASON_BASE]) и с сезонными пересечься не могут.
    val seasonsOnly = known.filterNot { it.isSpecial }
    val specials = known.filter { it.isSpecial }
    val byNumber = seasonsOnly.associateByTo(LinkedHashMap()) { it.seasonNumber }
    for ((source, seasons) in discovered.groupBy { it.source }) {
        mergeOneSourceInto(byNumber, seasonsOnly, seasons, source)
    }
    return byNumber.values.sortedBy { it.seasonNumber } + specials
}

/**
 * Раскладки разных источников сливаются по отдельности: шкалы у них свои, и сцеплять с
 * каталогом нужно каждую саму по себе, иначе номера перемешаются.
 */
private fun mergeOneSourceInto(
    byNumber: LinkedHashMap<Int, SeasonInfo>,
    known: List<SeasonInfo>,
    discovered: List<DiscoveredSeason>,
    source: String,
) {
    // Шкала источника может расходиться с каталожной: у «Повар-боец Сома» шесть каталожных
    // сезонов против пяти у сайтов. Без трансляции число серий уезжает в чужую строку.
    val alignment = alignSeasonScales(
        catalogue = known.map { SeasonSpan(it.seasonNumber, it.episodes) },
        source = discovered.map { SeasonSpan(it.seasonNumber, it.episodes) },
    )
    val sourceToCatalogue = alignment?.catalogueToSource
        ?.entries
        ?.groupBy({ it.value }, { it.key })
        .orEmpty()

    for (season in discovered) {
        if (season.seasonNumber <= 0 || season.episodes <= 0) continue
        // Номер из диапазона спецвыпусков источник выдать не может — это мусор из разбора
        // страницы, и пустив его дальше, мы бы затёрли им строку OVA.
        if (season.seasonNumber >= SPECIAL_SEASON_BASE) continue
        if (alignment != null) {
            // Сезон-склейка знает сумму по нескольким каталожным сезонам — переносить её
            // в одну строку значит её завысить. Каталог здесь точнее, оставляем как есть.
            if (season.seasonNumber !in alignment.oneToOneSourceSeasons) continue
            val target = sourceToCatalogue[season.seasonNumber]?.singleOrNull() ?: continue
            val existing = byNumber[target] ?: continue
            if (season.episodes > existing.episodes) {
                byNumber[target] = existing.copy(episodes = season.episodes, source = source)
            }
            continue
        }
        // Шкалы не сцепились — прежнее поведение: слияние по номеру как есть.
        val existing = byNumber[season.seasonNumber]
        byNumber[season.seasonNumber] = when {
            existing == null -> SeasonInfo(
                seasonNumber = season.seasonNumber,
                episodes = season.episodes,
                source = source,
            )
            season.episodes > existing.episodes ->
                existing.copy(episodes = season.episodes, source = source)
            else -> existing
        }
    }
}

internal data class SeasonDiscoveryRefresh(
    val seasons: List<SeasonInfo>,
    val removedSeasons: Int,
)

internal fun refreshSeasonDiscovery(
    previous: List<SeasonInfo>,
    freshCatalogue: List<SeasonInfo>,
    discovered: List<DiscoveredSeason>,
): SeasonDiscoveryRefresh {
    val seasons = mergeSeasonDiscovery(freshCatalogue, discovered)
    val freshNumbers = seasons.mapTo(HashSet()) { it.seasonNumber }
    return SeasonDiscoveryRefresh(
        seasons = seasons,
        removedSeasons = previous.count { it.seasonNumber !in freshNumbers },
    )
}

internal fun shouldRefreshSeasonDiscovery(
    explicitlyRequested: Boolean,
    known: List<SeasonInfo>,
): Boolean = explicitlyRequested || known.isNotEmpty()

/**
 * «Найти ещё»: добрать сезоны из источников ПРОСМОТРА, когда каталожные API их не знают.
 *
 * [SeasonEpisodesResolver] строит цепочку сезонов по графу франшизы AniList — это точно и
 * аккуратно, но упирается в то, что AniList считает сезоном. Сплошь и рядом у тайтла в каталоге
 * два «сезона», а плееры раздают пять: продолжения, у которых своя запись в каталоге не связана
 * рёбрами PREQUEL/SEQUEL, вообще нет, или франшиза разложена иначе. Источники просмотра при этом
 * знают ровно то, что можно посмотреть, — поэтому здесь мы спрашиваем именно их.
 *
 * Запускается ТОЛЬКО по кнопке. Это несколько поисковых запросов и разбор HTML на каждый источник:
 * в фоновом проходе по всей коллекции такое стоило бы сотен запросов ради тайтлов, у которых и так
 * всё сошлось.
 *
 * Если сохранённого списка ещё нет, результат становится первым раскладом. Если список уже есть,
 * кнопка работает как полный refresh: каталог и streaming-источники опрашиваются заново, а старая
 * раскладка атомарно заменяется свежей. При полном сетевом отказе старый список сохраняется.
 */
class StreamingSeasonDiscovery(
    private val kodikDirectSearch: KodikDirectSearch,
    private val jutSuSource: JutSuSource,
    private val animeHeavenSource: AnimeHeavenSource,
    private val localDataSource: AnimeLocalDataSource,
    private val store: SeasonEpisodesStore,
    private val seasonEpisodesResolver: SeasonEpisodesResolver,
) {

    /** Чем закончился поиск — ровно то, что нужно показать пользователю одной строкой. */
    sealed interface Outcome {
        /** Найдены новые сезоны и/или у известных прибавилось серий. */
        data class Updated(val seasons: List<SeasonInfo>, val addedSeasons: Int, val addedEpisodes: Int) : Outcome
        /** Повторный ручной поиск полностью заменил старую раскладку свежей. */
        data class Refreshed(val seasons: List<SeasonInfo>, val removedSeasons: Int) : Outcome
        /** Источники ответили, но нового не нашлось. */
        data object NothingNew : Outcome
        /** Ни один источник не отдал ни одного сезона (тайтла у них нет либо сеть легла). */
        data object NotFound : Outcome
        /** Для этого типа медиа нет настроенного источника потоковых сезонов. */
        data object NotConfigured : Outcome
    }

    suspend fun discover(animeId: String, forceRefresh: Boolean = false): Outcome {
        store.ensureLoaded()
        val anime = localDataSource.getAnimeById(animeId) ?: return Outcome.NotFound
        if (anime.mediaType != MediaType.ANIME) return Outcome.NotConfigured

        val previous = store.entryFor(animeId)
        val known = previous?.seasons.orEmpty()
        if (shouldRefreshSeasonDiscovery(forceRefresh, known)) {
            return refresh(anime, previous)
        }

        val discovered = queryAllSources(anime)
        if (discovered.isEmpty()) {
            Log.i(TAG, "No seasons from streaming sources for \"${anime.title}\"")
            return Outcome.NotFound
        }

        val merged = mergeSeasonDiscovery(known, discovered)
        val addedSeasons = merged.size - known.size
        val addedEpisodes = merged.sumOf { it.episodes } - known.sumOf { it.episodes }
        if (addedSeasons <= 0 && addedEpisodes <= 0) {
            Log.i(TAG, "Streaming sources agree with the catalogue for \"${anime.title}\"")
            return Outcome.NothingNew
        }

        store.put(
            SeasonEpisodesEntry(
                animeId = animeId,
                seasons = merged,
                // Найденное сверх каталога — не повод считать расклад окончательным: фоновый
                // проход должен и дальше дотягивать номера серий у онгоингов.
                complete = previous?.complete == true && merged.none { it.ongoing },
                resolvedAt = System.currentTimeMillis(),
                schema = SeasonEpisodesEntry.CURRENT_SCHEMA,
            )
        )
        Log.i(
            TAG,
            "\"${anime.title}\": +$addedSeasons season(s), +$addedEpisodes episode(s) " +
                "→ ${merged.map { "S${it.seasonNumber}=${it.episodes}(${it.source})" }}",
        )
        return Outcome.Updated(merged, addedSeasons, addedEpisodes)
    }

    private suspend fun refresh(
        anime: Anime,
        previous: SeasonEpisodesEntry?,
    ): Outcome {
        val catalogue = runCatching {
            seasonEpisodesResolver.resolve(anime, includeStoredStreaming = false)
        }.onFailure {
            Log.i(TAG, "Catalogue refresh failed for \"${anime.title}\": ${it.message}")
        }.getOrNull()
        val discovered = queryAllSources(anime)
        val freshCatalogue = catalogue?.seasons.orEmpty()
        val catalogueIsReliable = freshCatalogue.any { it.source != "local" }

        // A transient outage must not turn a multi-season cache into a local one-season fallback.
        if (discovered.isEmpty() && !catalogueIsReliable) {
            Log.i(TAG, "Refresh produced no reliable seasons for \"${anime.title}\"; keeping cache")
            return Outcome.NotFound
        }

        val refreshed = refreshSeasonDiscovery(
            previous = previous?.seasons.orEmpty(),
            freshCatalogue = freshCatalogue,
            discovered = discovered,
        )
        if (refreshed.seasons.isEmpty()) return Outcome.NotFound

        store.put(
            SeasonEpisodesEntry(
                animeId = anime.id,
                seasons = refreshed.seasons,
                complete = catalogue?.complete == true &&
                    refreshed.seasons.none { it.ongoing } &&
                    refreshed.seasons.none { it.source in STREAMING_SOURCES },
                resolvedAt = System.currentTimeMillis(),
                schema = SeasonEpisodesEntry.CURRENT_SCHEMA,
            )
        )
        Log.i(
            TAG,
            "\"${anime.title}\": full refresh removed=${refreshed.removedSeasons} " +
                "→ ${refreshed.seasons.map { "S${it.seasonNumber}=${it.episodes}(${it.source})" }}",
        )
        return Outcome.Refreshed(refreshed.seasons, refreshed.removedSeasons)
    }

    /**
     * Источники опрашиваются параллельно и под общим таймаутом: они независимы, а самый медленный
     * из них не должен держать кнопку. Упавший просто выпадает из выдачи — как в `SourceEngine`.
     */
    private suspend fun queryAllSources(anime: Anime): List<DiscoveredSeason> = supervisorScope {
        listOf(
            "Kodik" to suspend { kodikDirectSearch.findSeasons(anime) },
            "jut.su" to suspend { jutSuSource.findSeasons(anime) },
            "AnimeHeaven" to suspend { animeHeavenSource.findSeasons(anime) },
        ).map { (label, block) ->
            async {
                runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block() } }
                    .onFailure { Log.i(TAG, "$label season discovery failed: ${it.message}") }
                    .getOrNull()
                    .orEmpty()
            }
        }.awaitAll().flatten()
    }

    companion object {
        private const val TAG = "SeasonDiscovery"
        private const val SOURCE_TIMEOUT_MS = 25_000L

        /**
         * Значения [SeasonInfo.source], означающие «сезон пришёл от источника просмотра».
         * По ним резолвер отличает найденное вручную от собственного результата и не затирает его.
         */
        val STREAMING_SOURCES = setOf("Kodik", "jut.su", "AnimeHeaven")
    }
}
