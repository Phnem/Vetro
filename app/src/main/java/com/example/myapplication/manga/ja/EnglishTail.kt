package com.example.myapplication.manga.ja

import android.util.Log
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.VetroMangaSource
import com.example.myapplication.manga.domain.sortedForReading
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** Каталог, который умеет находить мангу по номеру AniList. */
interface AniListLookup {
    suspend fun findByAniList(aniListId: Int, titles: List<String>): MangaItem?
}

/**
 * Английский хвост: главы на английском, которых ещё нет на языке пользователя. Работает как
 * японская цепочка, независимо от того, откуда читает пользователь, и нужен ровно тогда, когда
 * английский перевод вышел раньше русского (а японского оригинала в свободном доступе нет).
 */
interface EnglishTail {
    suspend fun chaptersAfter(
        animeId: String,
        queries: List<String>,
        lastKnown: Double,
        allowSearch: Boolean,
    ): List<MangaChapter>
}

/** Какой тайтл MangaDex соответствует тайтлу коллекции; пустой [mangaKey] - искали и не нашли. */
@Serializable
data class EnMatch(
    val mangaKey: String? = null,
    val title: String? = null,
    val checkedAt: Long = 0L,
)

/**
 * Английские главы с MangaDex. Тайтл ищется не по названию, а по номеру AniList: MangaDex хранит его
 * в карточке, поэтому подмешать чужую мангу с похожим названием нельзя. Номер AniList берётся тем же
 * способом, что и японское название ([NativeTitles]).
 *
 * Читаются только главы, которые лежат на самом MangaDex и открываются: ссылки на сайты издателей
 * и пустые главы источник отсеивает сам.
 */
class MangaDexEnglishTail(
    private val catalog: AniListLookup,
    private val source: VetroMangaSource,
    private val natives: NativeTitles,
    private val matches: JsonMapFileStore<EnMatch>,
    private val clock: () -> Long = System::currentTimeMillis,
) : EnglishTail {

    private val memo = ConcurrentHashMap<String, Pair<Long, List<MangaChapter>>>()

    override suspend fun chaptersAfter(
        animeId: String,
        queries: List<String>,
        lastKnown: Double,
        allowSearch: Boolean,
    ): List<MangaChapter> {
        matches.ensureLoaded()
        val known = matches[animeId]
        val match = when {
            known?.mangaKey != null -> known
            known != null && clock() - known.checkedAt < MISS_TTL_MS -> return emptyList()
            !allowSearch -> return emptyList()
            else -> discover(animeId, queries)
        } ?: return emptyList()
        return fetchAfter(match, lastKnown)
    }

    private suspend fun discover(animeId: String, queries: List<String>): EnMatch? {
        val native = natives.resolve(animeId, queries)
        val aniListId = native?.aniListId
        val item = if (aniListId == null) null else {
            runCatching { withTimeoutOrNull(SEARCH_TIMEOUT_MS) { catalog.findByAniList(aniListId, queries) } }
                .onFailure { Log.w(TAG, "mangadex lookup failed: ${it.message}") }
                .getOrNull()
        }
        val match = EnMatch(mangaKey = item?.key, title = item?.title, checkedAt = clock())
        if (item != null || aniListId != null) matches.update { it + (animeId to match) }
        return match.takeIf { it.mangaKey != null }
    }

    private suspend fun fetchAfter(match: EnMatch, lastKnown: Double): List<MangaChapter> {
        val key = requireNotNull(match.mangaKey)
        val now = clock()
        val all = memo[key]?.takeIf { now - it.first < MEMO_TTL_MS }?.second ?: run {
            val fetched = withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) {
                runCatching { source.chapters(MangaItem(source.id, key, match.title.orEmpty())) }
                    .onFailure { Log.w(TAG, "english chapters failed: ${it.message}") }
                    .getOrNull()
            }
            if (fetched != null) memo[key] = now to fetched
            fetched.orEmpty()
        }
        return all
            .filter { it.language == "en" && !it.paid && it.number != null && it.number!! > lastKnown }
            .groupBy { it.number }
            // Одну главу могли выложить несколько команд: берём самую свежую публикацию.
            .map { (_, versions) -> versions.maxBy { it.publishedAt } }
            .sortedForReading()
    }

    private companion object {
        const val TAG = "EnTail"
        const val SEARCH_TIMEOUT_MS = 15_000L
        const val CHAPTERS_TIMEOUT_MS = 25_000L
        const val MEMO_TTL_MS = 10L * 60 * 1000
        const val MISS_TTL_MS = 12L * 3600 * 1000
    }
}
