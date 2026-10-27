package com.example.myapplication.domain.recommendations

import android.util.Log
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.movie.TmdbIdQuery
import com.example.myapplication.network.movie.TmdbIdResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val TAG = "LibraryTmdbIdBackfill"

/** Журнал неудачных поисков: тайтл без совпадения не дёргает TMDB при каждом пересчёте. */
interface TmdbIdMissLedger {
    /** true, если по этому тайтлу с тем же названием и годом уже искали и совпадения не нашли недавно. */
    suspend fun isRecentMiss(animeId: String, fingerprint: String, nowMillis: Long): Boolean

    suspend fun recordMiss(animeId: String, fingerprint: String, nowMillis: Long, reason: String)
}

/**
 * Сидам фильмов и сериалов нужен TMDB id, а у записей, добавленных не из TMDB, его нет. Здесь он
 * подбирается автоматически по названию, году и типу ([TmdbIdResolution] проверяет совпадение) и
 * сохраняется в записи, так что поиск выполняется один раз на тайтл. Неоднозначные и не найденные
 * тайтлы остаются без id и запоминаются на [MISS_TTL_MILLIS]; id не угадывается.
 */
class LibraryTmdbIdBackfill(
    private val resolve: suspend (TmdbIdQuery) -> TmdbIdResolution,
    private val persist: suspend (animeId: String, tmdbId: Int) -> Unit,
    private val ledger: TmdbIdMissLedger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pauseMillis: Long = REQUEST_PAUSE_MILLIS,
) {
    /** Возвращает библиотеку с подставленными id; за один вызов обрабатывается не больше [limit] тайтлов. */
    suspend fun run(library: List<Anime>, limit: Int = MAX_PER_RUN): List<Anime> {
        val pending = library.filter { needsId(it) }
        if (pending.isEmpty()) return library

        val resolved = HashMap<String, Int>()
        var attempted = 0
        for (anime in pending) {
            if (attempted >= limit) break
            val now = nowMillis()
            val fingerprint = fingerprint(anime)
            if (ledger.isRecentMiss(anime.id, fingerprint, now)) continue
            attempted++
            val verdict = try {
                resolve(queryFor(anime))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TMDB id lookup failed for ${anime.id}: ${e.message}")
                TmdbIdResolution.Unavailable
            }
            when (verdict) {
                is TmdbIdResolution.Matched -> {
                    persist(anime.id, verdict.tmdbId)
                    resolved[anime.id] = verdict.tmdbId
                }
                TmdbIdResolution.NoMatch -> ledger.recordMiss(anime.id, fingerprint, now, "no-match")
                TmdbIdResolution.Ambiguous -> ledger.recordMiss(anime.id, fingerprint, now, "ambiguous")
                // Нет ключа или сети: остальным повезёт не больше, а запоминать отказ нельзя.
                TmdbIdResolution.Unavailable -> break
            }
            if (pauseMillis > 0) delay(pauseMillis)
        }
        if (resolved.isEmpty()) return library
        return library.map { anime -> resolved[anime.id]?.let { anime.copy(tmdbId = it) } ?: anime }
    }

    private fun needsId(anime: Anime): Boolean =
        anime.tmdbId == null &&
            (anime.mediaType == MediaType.MOVIE || anime.mediaType == MediaType.SERIES) &&
            anime.watchStatus != WatchStatus.DROPPED

    private fun queryFor(anime: Anime): TmdbIdQuery = TmdbIdQuery(
        titles = listOfNotNull(anime.titleEn, anime.title, anime.titleRu),
        year = anime.releaseYear,
        contentType = if (anime.mediaType == MediaType.MOVIE) AppContentType.MOVIE else AppContentType.SERIES,
    )

    /** Изменение названия или года делает прежний отрицательный результат недействительным. */
    private fun fingerprint(anime: Anime): String =
        "${anime.mediaType}|${anime.title}|${anime.titleEn.orEmpty()}|${anime.titleRu.orEmpty()}|${anime.releaseYear ?: 0}"

    companion object {
        const val MAX_PER_RUN = 12
        const val REQUEST_PAUSE_MILLIS = 250L
        const val MISS_TTL_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
