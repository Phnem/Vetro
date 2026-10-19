package com.example.myapplication.media.source.vfmodule

import com.example.myapplication.network.ids.CrossIds
import com.example.myapplication.network.ids.ResolveQuery
import com.example.myapplication.network.ids.ResolvedTitle
import com.example.myapplication.network.ids.TitleContribution
import com.example.myapplication.network.ids.TitleIdContributor
import com.example.myapplication.network.ids.TitleKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * VETRO-TAIL[title-id-source]
 *
 * Контрибьютор метаданных и кросс-ID из VFmodule (Alloha API + агрегатор плееров Kinobox).
 * Вызывается после встроенных шагов [com.example.myapplication.network.ids.TitleIdResolver]
 * только тогда, когда в записи остались незаполненные ID (Kinopoisk / IMDb / TMDB) или названия.
 *
 * Правила хвоста (`docs/agents/tails.md`):
 *  - только дополняет недостающие поля, ничего не перезаписывает;
 *  - при отсутствии новых данных или ошибке сети возвращает `null`, не бросая исключений.
 */
class VfTitleIdContributor(
    private val allohaExtractor: AllohaExtractor,
    private val playersClient: KinoboxPlayersClient,
    private val allohaTokenProvider: () -> String? = { null },
) : TitleIdContributor {

    override val name: String = "VFmodule"

    override suspend fun contribute(query: ResolveQuery, soFar: ResolvedTitle): TitleContribution? {
        val currentIds = soFar.ids
        val hasTmdb = currentIds.tmdbMovie != null || currentIds.tmdbTv != null
        val idsComplete = currentIds.kinopoisk != null && currentIds.imdb != null && hasTmdb
        val textComplete = !soFar.titleRu.isNullOrBlank() &&
            !soFar.descriptionRu.isNullOrBlank() &&
            !soFar.posterUrl.isNullOrBlank()

        if (idsComplete && textComplete) {
            return null
        }

        val searchTitle = soFar.titleRu
            ?: query.titleRu
            ?: query.title
            ?: soFar.titleEn
            ?: query.titleEn
        val tmdbId = when (query.kind) {
            TitleKind.MOVIE -> currentIds.tmdbMovie ?: currentIds.tmdbTv
            TitleKind.SERIES, TitleKind.ANIME -> currentIds.tmdbTv ?: currentIds.tmdbMovie
        }

        var contributedIds = CrossIds()
        var titleRu: String? = null
        var titleEn: String? = null
        val altTitles = mutableListOf<String>()
        var descriptionRu: String? = null
        var posterUrl: String? = null

        // 1. Если задан токен Alloha, пробуем обогатить карточку через Alloha API
        val token = allohaTokenProvider()?.trim()?.takeIf { it.isNotEmpty() }
        if (token != null) {
            val allohaObj = try {
                allohaExtractor.queryApi(
                    token = token,
                    kinopoiskId = currentIds.kinopoisk,
                    imdbId = currentIds.imdb,
                    title = searchTitle,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }

            val data = allohaObj?.get("data") as? JsonObject ?: allohaObj
            if (data != null) {
                val idsObj = data["ids"] as? JsonObject
                val kp = data.intField("id_kp", "kp", "kinopoisk_id")
                    ?: idsObj?.intField("kp", "id_kp", "kinopoisk_id")
                val imdb = (
                    data.stringField("id_imdb", "imdb", "imdb_id")
                        ?: idsObj?.stringField("imdb", "id_imdb", "imdb_id")
                    )?.let(::normalizeImdb)
                val tmdb = data.intField("id_tmdb", "tmdb", "tmdb_id")
                    ?: idsObj?.intField("tmdb", "id_tmdb", "tmdb_id")
                contributedIds = contributedIds.merge(
                    CrossIds(
                        kinopoisk = kp,
                        imdb = imdb,
                        tmdbMovie = tmdb?.takeIf { query.kind == TitleKind.MOVIE },
                        tmdbTv = tmdb?.takeIf { query.kind != TitleKind.MOVIE },
                    )
                )
                titleRu = data.stringField("name", "title_ru", "russian")
                titleEn = data.stringField("original_name", "name_orig", "title_en")
                data.stringField("alternative_name")?.split(",", "/")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let(altTitles::addAll)
                descriptionRu = data.stringField("description", "plot")
                posterUrl = data.stringField("poster", "poster_url")
            }
        }

        // 2. Если всё ещё не хватает Kinopoisk ID или IMDb ID — извлекаем их из ссылок плееров балансеров
        val mergedSoFar = currentIds.merge(contributedIds)
        if (mergedSoFar.kinopoisk == null || mergedSoFar.imdb == null) {
            val hits = try {
                playersClient.fetchPlayers(
                    kinopoiskId = mergedSoFar.kinopoisk,
                    imdbId = mergedSoFar.imdb,
                    tmdbId = tmdbId,
                    title = searchTitle,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                emptyList()
            }

            for (hit in hits) {
                val kpFromUrl = extractKinopoiskIdFromUrl(hit.iframeUrl)
                val imdbFromUrl = extractImdbIdFromUrl(hit.iframeUrl)
                if (kpFromUrl != null || imdbFromUrl != null) {
                    contributedIds = contributedIds.merge(
                        CrossIds(
                            kinopoisk = kpFromUrl,
                            imdb = imdbFromUrl,
                        )
                    )
                }
            }
        }

        val hasNewId = (currentIds.kinopoisk == null && contributedIds.kinopoisk != null) ||
            (currentIds.imdb == null && contributedIds.imdb != null) ||
            (currentIds.tmdbMovie == null && contributedIds.tmdbMovie != null) ||
            (currentIds.tmdbTv == null && contributedIds.tmdbTv != null)
        val hasNewText = (soFar.titleRu.isNullOrBlank() && !titleRu.isNullOrBlank()) ||
            (soFar.titleEn.isNullOrBlank() && !titleEn.isNullOrBlank()) ||
            (soFar.descriptionRu.isNullOrBlank() && !descriptionRu.isNullOrBlank()) ||
            (soFar.posterUrl.isNullOrBlank() && !posterUrl.isNullOrBlank()) ||
            altTitles.isNotEmpty()

        if (!hasNewId && !hasNewText) {
            return null
        }

        return TitleContribution(
            ids = contributedIds,
            titleRu = titleRu,
            titleEn = titleEn,
            altTitles = altTitles.distinct(),
            descriptionRu = descriptionRu,
            posterUrl = posterUrl,
        )
    }

    private fun JsonObject.stringField(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull?.trim()
            if (!v.isNullOrEmpty() && v != "null") return v
        }
        return null
    }

    private fun JsonObject.intField(vararg keys: String): Int? {
        for (k in keys) {
            val p = this[k]?.jsonPrimitive ?: continue
            val v = p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull()
            if (v != null && v > 0) return v
        }
        return null
    }

    companion object {
        private val KP_URL_REGEXES = listOf(
            Regex("""[?&](?:kp|kinopoisk|id_kp|kp_id)=(\d{3,10})\b""", RegexOption.IGNORE_CASE),
            Regex("""/(?:kp|kinopoisk)/(\d{3,10})(?:[/?#]|$)""", RegexOption.IGNORE_CASE),
        )
        private val IMDB_URL_REGEX = Regex("""\b(tt\d{5,10})\b""", RegexOption.IGNORE_CASE)

        internal fun extractKinopoiskIdFromUrl(url: String): Int? {
            for (regex in KP_URL_REGEXES) {
                val match = regex.find(url) ?: continue
                val id = match.groupValues[1].toIntOrNull()
                if (id != null && id > 0) return id
            }
            return null
        }

        internal fun extractImdbIdFromUrl(url: String): String? {
            return IMDB_URL_REGEX.find(url)?.groupValues?.get(1)?.lowercase()
        }

        private fun normalizeImdb(raw: String): String? {
            val cleaned = raw.trim().lowercase()
            return cleaned.takeIf { IMDB_URL_REGEX.matches(it) }
        }
    }
}
