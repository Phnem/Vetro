package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.local.VetroLinks
import com.example.myapplication.data.models.Anime
import com.example.myapplication.mediacatalog.HttpVetroCatalog
import com.example.myapplication.mediacatalog.VetroCatalog
import com.example.myapplication.mediacatalog.VetroLinkService
import com.example.myapplication.network.ApiSearchResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Отсев кандидатов по каталогу Vetro: у кандидата узнаётся Vetro id (по его id в AniList, MAL, Shikimori, TMDB, IMDb), и если
 * такой id уже есть у тайтла коллекции, кандидат — дубль и убирается, даже когда id и название в разных источниках не совпали.
 * Узнанный Vetro id остаётся на кандидате ([com.example.myapplication.network.ExternalIds.vetro]), поэтому склейка дублей между
 * источниками дальше сводит записи одного тайтла в одну карточку.
 *
 * Сбой каталога никогда не прячет рекомендации: что не удалось узнать, остаётся как есть, прежние фильтры работают дальше.
 */
class VetroIdentityFilter(
    private val catalog: VetroCatalog,
    private val store: VetroLinks,
    private val linker: VetroLinkService,
) {
    /** `kind|ключ` → Vetro id; пустая строка — каталог проверили, такого тайтла в нём нет. Живёт, пока жив процесс. */
    private val resolved = ConcurrentHashMap<String, String>()

    suspend fun apply(pool: List<PoolEntry>, library: List<Anime>): List<PoolEntry> {
        if (pool.isEmpty()) return pool
        return try {
            // Свежедобавленные тайтлы коллекции тоже получают Vetro id (быстро: по id, пачками); часть остатка — по названию.
            runCatching { linker.linkQuick(titleLimit = QUICK_TITLES) }.onFailure { if (it is CancellationException) throw it }
            val libraryIds = library.mapTo(HashSet()) { it.id }
            val owned = store.all().filter { it.animeId in libraryIds }.mapNotNullTo(HashSet()) { it.vetroId }

            resolve(pool)
            pool.mapNotNull { entry ->
                val vetroId = vetroIdOf(entry) ?: return@mapNotNull entry
                if (vetroId in owned) null else entry.withVetroId(vetroId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            pool
        }
    }

    /** Спрашивает каталог про все ещё не известные ключи кандидатов: пачки по 40, отдельно по базам. */
    private suspend fun resolve(pool: List<PoolEntry>) = coroutineScope {
        val gate = Semaphore(PARALLEL)
        val wanted = pool.flatMap { entry -> keysOf(entry).map { entry.kind() to it } }.distinct().filter { (kind, key) -> !resolved.containsKey("$kind|$key") }
        wanted.groupBy({ it.first }, { it.second }).flatMap { (kind, keys) ->
            keys.chunked(HttpVetroCatalog.MAX_IDS).map { chunk ->
                async {
                    gate.withPermit {
                        catalog.byExternalIds(chunk, kind).onSuccess { hits ->
                            val byVia = hits.mapNotNull { h -> h.via?.let { it to h.id } }.toMap()
                            chunk.forEach { key -> resolved["$kind|$key"] = byVia[key].orEmpty() }
                        }
                    }
                }
            }
        }.awaitAll()
    }

    private fun vetroIdOf(entry: PoolEntry): String? =
        keysOf(entry).firstNotNullOfOrNull { key -> resolved["${entry.kind()}|$key"]?.takeIf { it.isNotEmpty() } }

    private fun PoolEntry.withVetroId(id: String): PoolEntry =
        copy(result = result.copy(externalIds = result.externalIds.copy(vetro = id)))

    private fun PoolEntry.kind(): String = when (contentType) {
        ContentType.ANIME -> "anime"
        ContentType.MOVIE -> "movie"
        ContentType.TV -> "tv"
    }

    companion object {
        private const val PARALLEL = 3
        /** Сколько тайтлов коллекции без id других сервисов добирается по названию за один пересчёт рекомендаций. */
        const val QUICK_TITLES = 25

        /**
         * Ключи кандидата для каталога в порядке доверия. Аниме ищется по id аниме-сервисов, фильмы и сериалы по TMDB и IMDb:
         * номера TMDB у фильма и сериала разные, поэтому ключ всегда спрашивается внутри своей базы.
         */
        internal fun keysOf(entry: PoolEntry): List<String> = keysOf(entry.result, entry.contentType)

        internal fun keysOf(result: ApiSearchResult, type: ContentType): List<String> {
            val ext = result.externalIds
            val own = result.externalId?.takeIf { it.isNotBlank() }
            return buildList {
                if (type == ContentType.ANIME) {
                    if (own != null) when (result.source.lowercase()) {
                        "anilist" -> add("anilist:$own")
                        "shikimori" -> add("shikimori:$own")
                        "mal", "jikan" -> add("mal:$own")
                    }
                    ext.anilist?.let { add("anilist:$it") }
                    (ext.mal ?: result.malId)?.let { add("mal:$it") }
                    ext.shikimori?.let { add("shikimori:$it") }
                } else {
                    if (own != null && result.source.equals("tmdb", ignoreCase = true)) add("tmdb:$own")
                    ext.tmdb?.let { add("tmdb:$it") }
                    ext.imdb?.takeIf { it.isNotBlank() }?.let { add("imdb:$it") }
                }
            }.distinct().filter { KEY.matches(it) }
        }

        private val KEY = Regex("""^(anilist|mal|shikimori|tmdb|imdb):[A-Za-z0-9]{1,16}$""")
    }
}
