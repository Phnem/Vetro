package com.example.myapplication.mediacatalog

import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.VetroLink
import com.example.myapplication.data.local.VetroLinks
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.normalizeForSearch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Коллекция пользователя глазами службы привязки: что в ней есть и как проставить обложку. */
interface VetroLibrary {
    fun items(): List<Anime>

    /** Есть ли у тайтла обложка на устройстве. */
    fun hasCover(anime: Anime): Boolean

    /** Проставляет обложку, только если у тайтла её всё ещё нет (за время загрузки его могли отредактировать). */
    suspend fun setCover(animeId: String, fileName: String): Boolean

    /** Путь к файлу обложки на устройстве; null, если обложки нет или файл не найден. */
    fun coverFile(anime: Anime): String? = null

    /** Подменяет обложку новой, только если у тайтла всё ещё старая [oldName]; старый файл удаляется. */
    suspend fun replaceCover(animeId: String, oldName: String, newName: String): Boolean = false

    /** Убирает обложку, которая оказалась заглушкой, только если она всё ещё [oldName]: тайтл без обложки попадает в обычный поиск обложки. */
    suspend fun dropCover(animeId: String, oldName: String): Boolean = false
}

class AnimeVetroLibrary(
    private val animeData: AnimeLocalDataSource,
    private val images: ImageStorageRepository,
) : VetroLibrary {
    override fun items(): List<Anime> = animeData.getAllAnimeList()

    override fun hasCover(anime: Anime): Boolean = anime.imageFileName?.let { images.hasLocalImage(it) } == true

    override suspend fun setCover(animeId: String, fileName: String): Boolean {
        val current = animeData.getAnimeById(animeId) ?: return false
        if (hasCover(current)) return false
        animeData.updateAnime(current.copy(imageFileName = fileName))
        return true
    }

    override fun coverFile(anime: Anime): String? = anime.imageFileName?.let { images.getImageFilePath(it) }

    override suspend fun replaceCover(animeId: String, oldName: String, newName: String): Boolean {
        val current = animeData.getAnimeById(animeId) ?: return false
        if (current.imageFileName != oldName) return false
        animeData.updateAnime(current.copy(imageFileName = newName))
        images.deleteImage(oldName)
        return true
    }

    override suspend fun dropCover(animeId: String, oldName: String): Boolean {
        val current = animeData.getAnimeById(animeId) ?: return false
        if (current.imageFileName != oldName) return false
        animeData.updateAnime(current.copy(imageFileName = null))
        images.deleteImage(oldName)
        return true
    }
}

enum class LinkPhase { MATCHING, COVERS }

data class LinkProgress(val phase: LinkPhase, val done: Int, val total: Int)

sealed interface LinkOutcome {
    /** Привязка прошла. [notFound] — тайтлы, которых в каталоге нет; [pending] — не успели проверить по названию. */
    data class Done(val linked: Int, val notFound: Int, val pending: Int, val covers: Int) : LinkOutcome

    /** Каталог недоступен (нет сети): ничего не записано, повторить позже. */
    data object Unavailable : LinkOutcome
}

/**
 * Привязывает тайтлы коллекции к записям каталога Vetro (api.vetrostudio.bid) и докачивает недостающие обложки.
 *
 * Сначала по id других сервисов, которые уже есть у тайтла (AniList, MAL, Shikimori, TMDB, IMDb): это пачки по 40 и
 * точное совпадение. Остальные ищутся по названию внутри своей базы (аниме, фильмы или сериалы) и принимаются, только если
 * совпало название (с поправкой на год) или id, а ни один id не противоречит. Тайтл, которого в каталоге нет, помечается
 * «не найден» на неделю. При сбое сети ничего не помечается: вопрос откладывается, а не закрывается.
 */
class VetroLinkService(
    private val catalog: VetroCatalog,
    private val store: VetroLinks,
    private val library: VetroLibrary,
    /** Сохраняет скачанную обложку как файл тайтла и возвращает имя файла; null — не удалось декодировать. */
    private val storeCover: suspend (bytes: ByteArray, animeId: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    /** Пауза между запросами поиска по названию: у API лимит 120 запросов в минуту с одного адреса. */
    private val titleIntervalMs: Long = TITLE_INTERVAL_MS,
) {
    private val paceLock = Mutex()
    private var nextTitleRequestAt = 0L

    /** Выдерживает темп запросов поиска по названию между всеми параллельными задачами. */
    private suspend fun pace() {
        if (titleIntervalMs <= 0) return
        val wait = paceLock.withLock {
            val t = now()
            val at = maxOf(t, nextTitleRequestAt)
            nextTitleRequestAt = at + titleIntervalMs
            at - t
        }
        if (wait > 0) delay(wait)
    }

    /** Полный проход: id → названия → обложки. Идёт под экраном загрузки. */
    suspend fun run(
        onProgress: (LinkProgress) -> Unit = {},
        titleBudgetMs: Long = TITLE_BUDGET_MS,
        coverBudgetMs: Long = COVER_BUDGET_MS,
    ): LinkOutcome = coroutineScope {
        val items = library.items().filter { kindOf(it) != null }
        val stored = store.all().associateBy { it.animeId }
        store.delete(stored.keys - items.mapTo(HashSet()) { it.id })

        val started = now()
        val todo = items.filter { needsCheck(stored[it.id], started) }
        val hits = HashMap<String, VetroHit>() // vetro id → запись, чтобы не спрашивать дважды ради обложки
        val done = AtomicInteger(0)
        fun report() = onProgress(LinkProgress(LinkPhase.MATCHING, done.get(), todo.size))
        report()

        val byIds = matchByIds(todo)
        if (byIds.unavailable) return@coroutineScope LinkOutcome.Unavailable
        val links = HashMap<String, VetroLink>()
        byIds.found.forEach { (animeId, match) ->
            links[animeId] = VetroLink(animeId, match.hit.id, started, match.key)
            hits[match.hit.id] = match.hit
        }
        done.set(links.size)
        store.putAll(links.values)
        report()

        // Названия: по одному тайтлу на запрос, 4 параллельно, не дольше бюджета времени.
        val rest = todo.filter { it.id !in links }
        val deadline = started + titleBudgetMs
        var pending = 0
        val gate = Semaphore(TITLE_PARALLEL)
        val byTitle = rest.map { anime ->
            async {
                gate.withPermit {
                    if (now() > deadline) return@withPermit null
                    matchByTitle(anime, deadline).also { done.incrementAndGet(); report() }
                }
            }
        }.awaitAll()
        val batch = ArrayList<VetroLink>()
        var notFound = 0
        rest.zip(byTitle).forEach { (anime, outcome) ->
            when (outcome) {
                null, TitleOutcome.Failed -> pending++
                is TitleOutcome.Found -> {
                    links[anime.id] = VetroLink(anime.id, outcome.hit.id, now(), "title")
                    batch += links.getValue(anime.id)
                    hits[outcome.hit.id] = outcome.hit
                }
                TitleOutcome.NotFound -> { batch += VetroLink(anime.id, null, now(), null); notFound++ }
            }
        }
        store.putAll(batch)

        val covers = pullCovers(onProgress, coverBudgetMs, hits)
        LinkOutcome.Done(linked = links.size, notFound = notFound, pending = pending, covers = covers)
    }

    /**
     * Быстрый проход только по id других сервисов, без поиска по названию и обложек: пачками по 40, без записи «не найден».
     * Нужен рекомендациям, чтобы свежедобавленные тайтлы коллекции уже были с Vetro id. Сбой сети тихий.
     */
    suspend fun linkQuick(titleLimit: Int = 0): Unit = coroutineScope {
        val items = library.items().filter { kindOf(it) != null }
        val stored = store.all().associateBy { it.animeId }
        val todo = items.filter { stored[it.id] == null }
        if (todo.isEmpty()) return@coroutineScope
        val result = matchByIds(todo)
        val t = now()
        val links = ArrayList(result.found.map { (animeId, match) -> VetroLink(animeId, match.hit.id, t, match.key) })
        // Остаток первого прохода, не уложившийся в экран загрузки, добирается по немного в фоне.
        if (!result.unavailable && titleLimit > 0) {
            val deadline = t + TITLE_BUDGET_MS
            for (anime in todo.filter { it.id !in result.found }.take(titleLimit)) {
                when (val outcome = matchByTitle(anime, deadline)) {
                    is TitleOutcome.Found -> links += VetroLink(anime.id, outcome.hit.id, now(), "title")
                    TitleOutcome.NotFound -> links += VetroLink(anime.id, null, now(), null)
                    TitleOutcome.Failed -> break
                }
            }
        }
        store.putAll(links)
    }

    /**
     * Запись каталога для одного тайтла: по id других сервисов, затем по названию. Находку запоминает.
     * success(null) — каталог ответил, что такого тайтла в нём нет; failure — каталог недоступен (отвечать «нет» нельзя).
     */
    suspend fun resolve(anime: Anime): Result<VetroHit?> = coroutineScope {
        if (kindOf(anime) == null) return@coroutineScope Result.success(null)
        val byIds = matchByIds(listOf(anime))
        byIds.found[anime.id]?.let { match ->
            store.putAll(listOf(VetroLink(anime.id, match.hit.id, now(), match.key)))
            return@coroutineScope Result.success(match.hit)
        }
        if (byIds.unavailable) return@coroutineScope Result.failure(IllegalStateException("catalogue unavailable"))
        when (val outcome = matchByTitle(anime, now() + TITLE_BUDGET_MS)) {
            is TitleOutcome.Found -> {
                store.putAll(listOf(VetroLink(anime.id, outcome.hit.id, now(), "title")))
                Result.success(outcome.hit)
            }
            TitleOutcome.NotFound -> Result.success(null)
            TitleOutcome.Failed -> Result.failure(IllegalStateException("catalogue unavailable"))
        }
    }

    // ---- По id других сервисов ------------------------------------------------------------------------------------

    private class IdMatch(val hit: VetroHit, val key: String)

    private class IdResult(val found: Map<String, IdMatch>, val unavailable: Boolean)

    private suspend fun matchByIds(todo: List<Anime>): IdResult = coroutineScope {
        val found = HashMap<String, IdMatch>()
        val batches = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val gate = Semaphore(ID_PARALLEL)
        for ((kind, group) in todo.groupBy { kindOf(it)!! }) {
            val keysOf = group.associateWith { externalKeys(it).map { (k, v) -> "$k:$v" } }
            val byKey = HashMap<String, VetroHit>()
            keysOf.values.flatten().distinct().chunked(HttpVetroCatalog.MAX_IDS).map { chunk ->
                async {
                    gate.withPermit {
                        batches.incrementAndGet()
                        catalog.byExternalIds(chunk, kind).fold(
                            onSuccess = { hits -> synchronized(byKey) { hits.forEach { h -> h.via?.let { byKey.putIfAbsent(it, h) } } } },
                            onFailure = { if (it is CancellationException) throw it; failed.incrementAndGet() },
                        )
                    }
                }
            }.awaitAll()
            keysOf.forEach { (anime, keys) ->
                keys.firstNotNullOfOrNull { key -> byKey[key]?.let { IdMatch(it, key) } }?.let { found[anime.id] = it }
            }
        }
        IdResult(found, unavailable = batches.get() > 0 && failed.get() == batches.get())
    }

    // ---- По названию ----------------------------------------------------------------------------------------------

    private sealed interface TitleOutcome {
        class Found(val hit: VetroHit) : TitleOutcome
        data object NotFound : TitleOutcome
        data object Failed : TitleOutcome
    }

    private suspend fun matchByTitle(anime: Anime, deadline: Long): TitleOutcome {
        val kind = kindOf(anime) ?: return TitleOutcome.NotFound
        var failed = false
        for (query in titleQueries(anime)) {
            pace()
            if (now() > deadline) return TitleOutcome.Failed
            catalog.byTitle(query, kind).fold(
                onSuccess = { hits -> pickByTitle(anime, hits)?.let { return TitleOutcome.Found(it) } },
                onFailure = { if (it is CancellationException) throw it; failed = true },
            )
        }
        return if (failed) TitleOutcome.Failed else TitleOutcome.NotFound
    }

    // ---- Обложки --------------------------------------------------------------------------------------------------

    /** Тайтлам, у которых нет обложки на устройстве, а в каталоге есть, обложка скачивается и сохраняется. */
    private suspend fun pullCovers(onProgress: (LinkProgress) -> Unit, budgetMs: Long, known: Map<String, VetroHit>): Int = coroutineScope {
        val links = store.all().filter { it.vetroId != null }.associateBy { it.animeId }
        val byId = library.items().associateBy { it.id }
        val needing = links.values.mapNotNull { link -> byId[link.animeId]?.takeIf { !library.hasCover(it) }?.let { it to link.vetroId!! } }
        if (needing.isEmpty()) return@coroutineScope 0

        val hits = HashMap(known)
        needing.map { it.second }.filter { it !in hits }.distinct().chunked(HttpVetroCatalog.MAX_IDS).forEach { chunk ->
            catalog.byIds(chunk).onSuccess { list -> list.forEach { hits[it.id] = it } }
        }
        val withCover = needing.filter { (_, id) -> hits[id]?.hasCover == true }
        val total = withCover.size
        val deadline = now() + budgetMs
        val done = AtomicInteger(0)
        val saved = AtomicInteger(0)
        val gate = Semaphore(COVER_PARALLEL)
        onProgress(LinkProgress(LinkPhase.COVERS, 0, total))
        withCover.map { (anime, id) ->
            async {
                gate.withPermit {
                    if (now() <= deadline) {
                        val bytes = catalog.downloadCover(hits.getValue(id))?.getOrNull()
                        val name = bytes?.let { storeCover(it, anime.id) }
                        if (name != null && library.setCover(anime.id, name)) saved.incrementAndGet()
                    }
                    onProgress(LinkProgress(LinkPhase.COVERS, done.incrementAndGet(), total))
                }
            }
        }.awaitAll()
        saved.get()
    }

    companion object {
        /** «Не найден» не повторяем неделю: каталог пополняется, но не каждый день. */
        const val RETRY_MS = 7L * 24 * 60 * 60 * 1000
        const val TITLE_BUDGET_MS = 150_000L
        const val COVER_BUDGET_MS = 120_000L

        /** ~90 запросов в минуту при лимите API 120: остаётся запас на остальные запросы приложения. */
        const val TITLE_INTERVAL_MS = 650L
        private const val ID_PARALLEL = 3
        private const val TITLE_PARALLEL = 4
        private const val COVER_PARALLEL = 3

        internal fun needsCheck(link: VetroLink?, nowMs: Long): Boolean =
            link == null || (link.vetroId == null && nowMs - link.checkedAt > RETRY_MS)

        /** База каталога для типа записи; манги в каталоге нет. */
        fun kindOf(anime: Anime): String? = when (anime.mediaType) {
            MediaType.ANIME -> "anime"
            MediaType.MOVIE -> "movie"
            MediaType.SERIES -> "tv"
            MediaType.MANGA -> null
        }

        /** id тайтла в других базах в порядке доверия: `anilist` → `mal` → `shikimori`; `tmdb` → `imdb`. */
        internal fun externalKeys(anime: Anime): List<Pair<String, String>> = buildList {
            when (anime.mediaType) {
                MediaType.ANIME -> {
                    anime.anilistId?.let { add("anilist" to it.toString()) }
                    anime.malId?.let { add("mal" to it.toString()) }
                    anime.shikimoriId?.let { add("shikimori" to it.toString()) }
                    // id Shikimori аниме совпадает с id MAL.
                    if (anime.malId == null) anime.shikimoriId?.let { add("mal" to it.toString()) }
                }
                MediaType.MOVIE, MediaType.SERIES -> {
                    anime.tmdbId?.let { add("tmdb" to it.toString()) }
                    anime.imdbId?.takeIf { it.isNotBlank() }?.let { add("imdb" to it) }
                }
                MediaType.MANGA -> Unit
            }
        }

        internal fun titleQueries(anime: Anime): List<String> =
            listOfNotNull(anime.title, anime.titleEn, anime.titleRu).map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.normalizeForSearch() }.take(3)

        /**
         * Из ответа поиска выбирает тот же тайтл: id совпал, либо совпало название и год в пределах одного. Если хоть один id
         * противоречит (у записи каталога тот же сервис, но другой номер), это другой тайтл.
         */
        internal fun pickByTitle(anime: Anime, hits: List<VetroHit>): VetroHit? {
            val names = listOfNotNull(anime.title, anime.titleEn, anime.titleRu).map { it.normalizeForSearch() }.filter { it.length >= 2 }.toSet()
            val ids = externalKeys(anime)
            val year = anime.releaseYear?.takeIf { it > 0 }
            return hits.firstOrNull { hit ->
                if (ids.any { (k, v) -> hit.ext[k]?.let { it != v } == true }) return@firstOrNull false
                val idAgrees = ids.any { (k, v) -> hit.ext[k] == v }
                val titleAgrees = hit.titles.any { it.normalizeForSearch() in names }
                val yearAgrees = year == null || hit.year == null || abs(year - hit.year) <= 1
                idAgrees || (titleAgrees && yearAgrees)
            }
        }
    }
}
