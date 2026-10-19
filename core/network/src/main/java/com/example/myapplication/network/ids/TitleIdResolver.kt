package com.example.myapplication.network.ids

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.TmdbKind
import com.example.myapplication.network.titleKey
import kotlinx.coroutines.CancellationException

/** Что известно о тайтле до разбора и как его искать. */
data class ResolveQuery(
    val kind: TitleKind,
    val titleRu: String? = null,
    val title: String? = null,
    val titleEn: String? = null,
    val year: Int? = null,
    val known: CrossIds = CrossIds(),
)

/** Сводка по тайтлу: id во всех каталогах, названия и описание из всех источников. */
data class ResolvedTitle(
    val ids: CrossIds,
    val titleRu: String? = null,
    val titleEn: String? = null,
    val altTitles: List<String> = emptyList(),
    val descriptionRu: String? = null,
    val descriptionEn: String? = null,
    val posterUrl: String? = null,
    val ratingKp: Double? = null,
    val ratingImdb: Double? = null,
    /** Какие источники дали данные — для журнала и отладки. */
    val sources: Set<String> = emptySet(),
)

/**
 * Склейка информационной части: Wikidata, карта аниме, ARM, ПоискКино, TMDB и Википедия сводятся в
 * одну запись. Идея в порядке доверия — от id к названию, а не наоборот:
 *  1. известные id расширяются остальными (Wikidata, для аниме — карта Fribb и ARM);
 *  2. нет ни одного id — ищем по русской подписи в Wikidata, затем по названию в ПоискКино;
 *  3. недостающая пара TMDB↔IMDb добирается через `find` и внешние id TMDB;
 *  4. названия — настоящие переводы TMDB, русское описание — ПоискКино, затем Википедия.
 *
 * Каждый шаг независим и изолирован: сбой сети или выключенный ключ пропускают шаг, а не ломают
 * результат. Метод не бросает ничего, кроме отмены корутины.
 */
class TitleIdResolver(
    private val wikidata: WikidataClient,
    private val animeIds: AnimeIdMap,
    private val arm: ArmClient,
    private val poiskKino: PoiskKinoClient,
    private val wikipedia: WikipediaClient,
    private val tmdb: TmdbTitlesClient,
    /** VETRO-TAIL[title-id-source]: сюда Koin подставляет все зарегистрированные [TitleIdContributor]. */
    private val contributors: List<TitleIdContributor> = emptyList(),
) {
    suspend fun resolve(q: ResolveQuery): ResolvedTitle {
        val base = resolveBuiltIn(q)
        // VETRO-TAIL[title-id-source]: новые источники идут после встроенных и только дополняют.
        return contributors.fold(base) { acc, c ->
            val add = guarded { c.contribute(q, acc) } ?: return@fold acc
            acc.with(add, c.name)
        }
    }

    private suspend fun resolveBuiltIn(q: ResolveQuery): ResolvedTitle {
        val sources = linkedSetOf<String>()
        var ids = q.known
        var entity: WikidataEntity? = null

        // 1. Расширяем то, что уже известно.
        if (!ids.isEmpty) {
            entity = guarded { expandViaWikidata(q.kind, ids) }
            entity?.let { ids = ids.merge(it.ids); sources += "Wikidata" }
        }

        // 2. Ничего не известно — ищем по названию.
        if (ids.isEmpty) {
            entity = guarded { searchWikidataByTitle(q) }
            entity?.let { ids = ids.merge(it.ids); sources += "Wikidata" }
        }
        var poisk: PoiskKinoTitle? = null
        if (ids.isEmpty && q.kind != TitleKind.ANIME) {
            poisk = guarded { searchPoiskKino(q) }
            poisk?.let { ids = ids.merge(it.crossIds()); sources += "PoiskKino" }
        }

        // 3. Аниме: мост в TMDB/IMDb через карту и ARM.
        if (q.kind == TitleKind.ANIME) {
            guarded { expandAnime(ids) }?.let { ids = ids.merge(it.first); sources += it.second }
        }

        // 4. Пара TMDB ↔ IMDb.
        guarded { completeTmdbImdb(ids) }?.let { ids = ids.merge(it); sources += "TMDB" }

        // 5. ПоискКино по id Кинопоиска: русские название, описание, постер и оценки.
        val kinopoiskId = ids.kinopoisk
        if (poisk == null && kinopoiskId != null) {
            poisk = guarded { (poiskKino.byId(kinopoiskId) as? LookupResult.Found)?.value }
        }
        poisk?.let {
            ids = ids.merge(it.crossIds())
            sources += "PoiskKino"
        }

        // 6. Названия и описание из TMDB.
        val tmdbTitles = guarded { fetchTmdbTitles(q.kind, ids) }
        tmdbTitles?.let {
            ids = ids.merge(CrossIds(imdb = it.imdbId, tvdb = it.tvdbId))
            sources += "TMDB"
        }

        val titleRu = poisk?.nameRu ?: tmdbTitles?.titleRu ?: entity?.labelRu
        val titleEn = poisk?.nameEn ?: tmdbTitles?.titleEn ?: entity?.labelEn
        var descriptionRu = poisk?.description ?: tmdbTitles?.overviewRu ?: poisk?.shortDescription

        // 7. Описания нет нигде — русская Википедия.
        if (descriptionRu.isNullOrBlank()) {
            guarded { wikipediaDescription(ids, titleRu ?: q.titleRu) }?.let {
                descriptionRu = it
                sources += "Wikipedia"
            }
        }

        val alt = buildList {
            tmdbTitles?.altTitles?.let(::addAll)
            listOfNotNull(poisk?.nameOriginal, tmdbTitles?.titleOriginal).forEach(::add)
        }.distinct().filter { it != titleRu && it != titleEn }

        return ResolvedTitle(
            ids = ids,
            titleRu = titleRu,
            titleEn = titleEn,
            altTitles = alt,
            descriptionRu = descriptionRu?.takeIf { it.isNotBlank() },
            descriptionEn = tmdbTitles?.overviewEn,
            posterUrl = poisk?.posterUrl ?: tmdbTitles?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            ratingKp = poisk?.ratingKp,
            ratingImdb = poisk?.ratingImdb,
            sources = sources,
        )
    }

    private suspend fun expandViaWikidata(kind: TitleKind, ids: CrossIds): WikidataEntity? {
        val probes = buildList {
            ids.kinopoisk?.let { add(ExternalIdKind.KINOPOISK to it.toString()) }
            ids.imdb?.let { add(ExternalIdKind.IMDB to it) }
            if (kind != TitleKind.SERIES) ids.tmdbMovie?.let { add(ExternalIdKind.TMDB_MOVIE to it.toString()) }
            if (kind != TitleKind.MOVIE) ids.tmdbTv?.let { add(ExternalIdKind.TMDB_TV to it.toString()) }
            ids.tvdb?.let { add(ExternalIdKind.TVDB to it.toString()) }
            ids.mal?.let { add(ExternalIdKind.MAL to it.toString()) }
            ids.anilist?.let { add(ExternalIdKind.ANILIST to it.toString()) }
            ids.kitsu?.let { add(ExternalIdKind.KITSU to it.toString()) }
        }
        for ((kindOfId, value) in probes) {
            val found = (wikidata.byExternalId(kindOfId, value) as? LookupResult.Found)?.value
            best(found, kind)?.let { return it }
        }
        return null
    }

    private suspend fun searchWikidataByTitle(q: ResolveQuery): WikidataEntity? {
        val tries = buildList {
            q.titleRu?.takeIf { it.isNotBlank() }?.let { add(it to "ru") }
            q.title?.takeIf { it.isNotBlank() }?.let { add(it to if (hasCyrillic(it)) "ru" else "en") }
            q.titleEn?.takeIf { it.isNotBlank() }?.let { add(it to "en") }
        }.distinct()
        for ((label, lang) in tries) {
            val found = (wikidata.byLabel(label, lang) as? LookupResult.Found)?.value
            best(found, q.kind)?.let { return it }
        }
        return null
    }

    /** Из нескольких записей с одной подписью — та, что подходит по типу и известна большему числу каталогов. */
    private fun best(entities: List<WikidataEntity>?, kind: TitleKind): WikidataEntity? =
        entities.orEmpty()
            .filter { suitable(it.ids, kind) }
            .maxByOrNull { it.ids.weight }

    private fun suitable(ids: CrossIds, kind: TitleKind): Boolean = when (kind) {
        TitleKind.MOVIE -> ids.kinopoisk != null || ids.imdb != null || ids.tmdbMovie != null
        TitleKind.SERIES -> ids.tmdbTv != null || ids.tvdb != null || ids.kinopoisk != null || ids.imdb != null
        TitleKind.ANIME -> ids.mal != null || ids.anilist != null || ids.kitsu != null
    }

    private suspend fun searchPoiskKino(q: ResolveQuery): PoiskKinoTitle? {
        val queries = listOfNotNull(q.titleRu, q.title, q.titleEn).filter { it.isNotBlank() }.distinct()
        val wantedKeys = queries.map(::titleKey)
        for (query in queries) {
            val found = (poiskKino.search(query, q.year) as? LookupResult.Found)?.value ?: continue
            val match = found.firstOrNull { t ->
                val keys = listOfNotNull(t.nameRu, t.nameOriginal, t.nameEn).map(::titleKey)
                keys.any { it in wantedKeys } && typeFits(t, q.kind) && yearFits(t.year, q.year)
            }
            if (match != null) return match
        }
        return null
    }

    private fun typeFits(t: PoiskKinoTitle, kind: TitleKind): Boolean = when (kind) {
        TitleKind.MOVIE -> !t.isSeries
        TitleKind.SERIES -> t.isSeries
        TitleKind.ANIME -> true
    }

    private fun yearFits(found: Int?, wanted: Int?): Boolean =
        found == null || wanted == null || kotlin.math.abs(found - wanted) <= 1

    private suspend fun expandAnime(ids: CrossIds): Pair<CrossIds, String>? {
        val probes = buildList {
            ids.anilist?.let { add(ExternalIdKind.ANILIST to it.toString()) }
            ids.mal?.let { add(ExternalIdKind.MAL to it.toString()) }
            ids.kitsu?.let { add(ExternalIdKind.KITSU to it.toString()) }
            ids.tvdb?.let { add(ExternalIdKind.TVDB to it.toString()) }
            ids.tmdbTv?.let { add(ExternalIdKind.TMDB_TV to it.toString()) }
            ids.imdb?.let { add(ExternalIdKind.IMDB to it) }
        }
        for ((kind, value) in probes) {
            val found = (animeIds.find(kind, value) as? LookupResult.Found)?.value
            if (found != null) return found.ids to "anime-lists"
        }
        // Свежий тайтл, которого ещё нет в списке, — спрашиваем ARM по AniList/MAL.
        val armProbes = buildList {
            ids.anilist?.let { add(ArmClient.ArmSource.ANILIST to it.toString()) }
            ids.mal?.let { add(ArmClient.ArmSource.MAL to it.toString()) }
        }
        for ((source, value) in armProbes) {
            val found = (arm.ids(source, value) as? LookupResult.Found)?.value
            if (found != null) return found to "ARM"
        }
        return null
    }

    /** Если известен один из TMDB/IMDb, но не другой — добираем недостающий. */
    private suspend fun completeTmdbImdb(ids: CrossIds): CrossIds? {
        val hasTmdb = ids.tmdbMovie != null || ids.tmdbTv != null
        if (hasTmdb) return null
        ids.imdb?.let { imdb ->
            val found = (tmdb.findByImdb(imdb) as? LookupResult.Found)?.value ?: return@let
            return if (found.kind == TmdbKind.MOVIE) CrossIds(tmdbMovie = found.id) else CrossIds(tmdbTv = found.id)
        }
        ids.tvdb?.let { tvdb ->
            val found = (tmdb.findByTvdb(tvdb) as? LookupResult.Found)?.value ?: return@let
            return CrossIds(tmdbTv = found.id)
        }
        return null
    }

    private suspend fun fetchTmdbTitles(kind: TitleKind, ids: CrossIds): TmdbTitles? {
        val movie = ids.tmdbMovie
        val tv = ids.tmdbTv
        val target: Pair<TmdbKind, Int> = when {
            kind == TitleKind.MOVIE && movie != null -> TmdbKind.MOVIE to movie
            kind != TitleKind.MOVIE && tv != null -> TmdbKind.TV to tv
            movie != null -> TmdbKind.MOVIE to movie
            tv != null -> TmdbKind.TV to tv
            else -> return null
        }
        return (tmdb.titles(target.first, target.second) as? LookupResult.Found)?.value
    }

    private suspend fun wikipediaDescription(ids: CrossIds, fallbackTitle: String?): String? {
        val fromWikidata = ids.wikidata?.let { (wikidata.ruWikipediaTitle(it) as? LookupResult.Found)?.value }
        val title = (fromWikidata ?: fallbackTitle)?.takeIf { it.isNotBlank() } ?: return null
        return (wikipedia.summary(title, "ru") as? LookupResult.Found)?.value?.extract
    }

    private fun hasCyrillic(s: String): Boolean = s.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }

    /** Шаг не должен ронять разбор: сбой — это «нет данных». Отмена корутины пробрасывается. */
    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
