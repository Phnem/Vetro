package com.example.myapplication.domain.enrichment

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.network.ids.CrossIds
import com.example.myapplication.network.ids.ResolveQuery
import com.example.myapplication.network.ids.ResolvedTitle
import com.example.myapplication.network.ids.TitleIdResolver
import com.example.myapplication.network.ids.TitleKind
import kotlinx.coroutines.CancellationException

/**
 * Связывает информационную часть (id и названия из Wikidata, ПоискКино, TMDB, Википедии) с записью
 * коллекции. Для фильмов и сериалов недостающие `tmdb_id`, `kinopoisk_id`, `imdb_id`, русское и
 * английское названия берутся из [TitleIdResolver] и сохраняются в карточку — после этого любой
 * провайдер просмотра ищет по id или по русскому названию, а не по слабому совпадению строки.
 *
 * Пишутся только пустые поля: введённое пользователем или найденное раньше не затирается. Повторные
 * вызовы дёшевы — ответы всех источников лежат в кэше.
 */
class TitleInfoEnricher(
    private val resolver: TitleIdResolver,
    private val repository: AnimeRepository,
) {
    /** Что нашлось и записано; запись из базы при необходимости перечитывается вызывающим. */
    data class Outcome(val anime: Anime, val resolved: ResolvedTitle?, val changedFields: List<String>)

    suspend fun enrich(anime: Anime): Outcome {
        val kind = when (anime.mediaType) {
            MediaType.MOVIE -> TitleKind.MOVIE
            MediaType.SERIES -> TitleKind.SERIES
            else -> return Outcome(anime, null, emptyList())
        }
        val resolved = try {
            resolver.resolve(
                ResolveQuery(
                    kind = kind,
                    titleRu = anime.titleRu,
                    title = anime.title,
                    titleEn = anime.titleEn,
                    known = CrossIds(
                        kinopoisk = anime.kinopoiskId,
                        imdb = anime.imdbId,
                        tmdbMovie = anime.tmdbId.takeIf { kind == TitleKind.MOVIE },
                        tmdbTv = anime.tmdbId.takeIf { kind == TitleKind.SERIES },
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Outcome(anime, null, emptyList())
        }

        val changed = mutableListOf<String>()
        var updated = anime
        val tmdb = if (kind == TitleKind.MOVIE) resolved.ids.tmdbMovie else resolved.ids.tmdbTv
        if (anime.tmdbId == null && tmdb != null) {
            repository.setTmdbId(anime.id, tmdb); updated = updated.copy(tmdbId = tmdb); changed += "tmdb"
        }
        resolved.ids.kinopoisk?.takeIf { anime.kinopoiskId == null }?.let {
            repository.setKinopoiskId(anime.id, it); updated = updated.copy(kinopoiskId = it); changed += "kinopoisk"
        }
        resolved.ids.imdb?.takeIf { anime.imdbId == null }?.let {
            repository.setImdbId(anime.id, it); updated = updated.copy(imdbId = it); changed += "imdb"
        }
        resolved.titleRu?.takeIf { anime.titleRu.isNullOrBlank() && hasCyrillic(it) }?.let {
            repository.setTitleRu(anime.id, it); updated = updated.copy(titleRu = it); changed += "titleRu"
        }
        resolved.titleEn?.takeIf { anime.titleEn.isNullOrBlank() && !hasCyrillic(it) }?.let {
            repository.setTitleEn(anime.id, it); updated = updated.copy(titleEn = it); changed += "titleEn"
        }
        return Outcome(updated, resolved, changed)
    }

    private fun hasCyrillic(s: String): Boolean = s.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
}
