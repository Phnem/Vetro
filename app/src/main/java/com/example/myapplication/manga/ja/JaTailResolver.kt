package com.example.myapplication.manga.ja

import android.util.Log
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.VetroMangaSource
import com.example.myapplication.manga.domain.sortedForReading
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** Что нужно знать, чтобы найти японское продолжение тайтла. */
data class TailRequest(
    val animeId: String,
    /** Названия тайтла, какими их знает приложение и источник: по ним определяется японский оригинал. */
    val queries: List<String>,
    /** Номер последней главы на языке пользователя; японские главы берутся строго после неё. */
    val lastKnownNumber: Double,
)

/** Серия тайтла на одном японском сайте. */
@Serializable
data class JaHit(
    val sourceId: String,
    val seriesKey: String,
    val title: String = "",
)

/**
 * Найденные японские серии тайтла. [sourceId] == null и пустой [hits] - искали и не нашли (помним,
 * чтобы не искать снова и снова). Серия бывает сразу на нескольких сайтах (у одних бесплатна одна глава,
 * у других вся), поэтому храним все совпадения, а не только первое.
 */
@Serializable
data class JaMatch(
    val sourceId: String? = null,
    val seriesKey: String? = null,
    val title: String? = null,
    val checkedAt: Long = 0L,
    val hits: List<JaHit> = emptyList(),
) {
    /** Совпадения с учётом записей прежнего формата (одно совпадение в полях выше). */
    fun allHits(): List<JaHit> = hits.ifEmpty {
        if (sourceId != null && seriesKey != null) listOf(JaHit(sourceId, seriesKey, title.orEmpty())) else emptyList()
    }

    val found: Boolean get() = allHits().isNotEmpty()
}

/** Накопленная надёжность японского источника на этом устройстве. */
@Serializable
data class JaHealth(
    val ok: Int = 0,
    val fail: Int = 0,
    val latencyMs: Long = 0L,
)

/**
 * Японская цепочка источников - как цепочка источников аниме, но для хвоста манги.
 *
 * Не зависит от того, откуда пользователь читает (Remanga, MangaDex, что угодно): ей нужно знать
 * только номер последней главы на его языке. Дальше она находит японский оригинал на сайтах
 * издателей (по точному совпадению названия), берёт БЕСПЛАТНЫЕ главы с большим номером и отдаёт
 * их списком; переводит их уже ридер.
 *
 * Порядок опроса источников определяется накопленной надёжностью ([JaHealth]): тот, что чаще
 * отвечает и быстрее, спрашивается первым и выигрывает при совпадении.
 */
class JaTailResolver(
    private val sources: List<VetroMangaSource>,
    private val natives: NativeTitles,
    private val matches: JsonMapFileStore<JaMatch>,
    private val health: JsonMapFileStore<JaHealth>,
    /** Английский хвост: главы, которые на английском вышли раньше, чем на языке пользователя. */
    private val english: EnglishTail? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val memo = ConcurrentHashMap<String, Pair<Long, List<MangaChapter>>>()
    private val readable = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun sourceById(id: String): VetroMangaSource? = sources.firstOrNull { it.id.value == id }

    /** Полный путь: при необходимости ищет серию. Вызывает экран глав. */
    suspend fun resolve(request: TailRequest): List<MangaChapter> {
        matches.ensureLoaded()
        val known = matches[request.animeId]
        val match = when {
            known?.found == true -> known
            known != null && clock() - known.checkedAt < MISS_TTL_MS -> return emptyList()
            else -> discover(request)
        } ?: return emptyList()
        return chaptersAfter(match, request.lastKnownNumber)
    }

    /** Лёгкий путь для фона: только по уже найденному совпадению, без поиска. */
    suspend fun cached(animeId: String, lastKnownNumber: Double): List<MangaChapter> {
        matches.ensureLoaded()
        val match = matches[animeId]?.takeIf { it.found } ?: return emptyList()
        return chaptersAfter(match, lastKnownNumber)
    }

    /**
     * Дописывает к главам источника японский хвост. Якорь - последняя глава на языке пользователя
     * ([preferredLanguage]); без языка или без единой главы на нём хвост не строится: "после чего"
     * брать японские главы, неизвестно. [allowSearch] = false - фоновый режим, поиск серии не запускается.
     */
    suspend fun withTail(
        base: List<MangaChapter>,
        animeId: String,
        preferredLanguage: String?,
        queries: List<String>,
        allowSearch: Boolean,
    ): List<MangaChapter> {
        if (preferredLanguage == null) return base
        val last = base.filter { it.language == preferredLanguage && !it.paid }
            .mapNotNull { it.number }.maxOrNull() ?: return base
        val japanese = runCatching {
            if (allowSearch) resolve(TailRequest(animeId, queries, last)) else cached(animeId, last)
        }.onFailure { Log.w(TAG, "tail failed: ${it.message}") }.getOrDefault(emptyList())
        val englishChapters = englishTail(base, animeId, preferredLanguage, queries, last, allowSearch)
        val tail = mergeByNumber(japanese, englishChapters)
        Log.i(TAG, "tail for $animeId: lang=$preferredLanguage last=$last ja=${japanese.size} en=${englishChapters.size} merged=${tail.size} search=$allowSearch")
        if (tail.isEmpty()) return base
        val have = base.mapTo(HashSet()) { it.sourceId to it.key }
        return base + tail.filter { (it.sourceId to it.key) !in have }
    }

    /**
     * Английские главы новее последней главы пользователя. Не нужны, если пользователь и так читает
     * по-английски или если сам источник уже отдаёт английский (тогда они и так есть в списке).
     */
    private suspend fun englishTail(
        base: List<MangaChapter>,
        animeId: String,
        preferredLanguage: String,
        queries: List<String>,
        last: Double,
        allowSearch: Boolean,
    ): List<MangaChapter> {
        val finder = english ?: return emptyList()
        if (preferredLanguage == "en" || base.any { it.language == "en" }) return emptyList()
        return runCatching { finder.chaptersAfter(animeId, queries, last, allowSearch) }
            .onFailure { Log.w(TAG, "english tail failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * Номер, который есть и на японском, и на английском, берём с японского: оригинал читается на
     * телефоне без лишнего пересказа, а английский заполняет только то, чего в оригинале нет.
     */
    private fun mergeByNumber(japanese: List<MangaChapter>, english: List<MangaChapter>): List<MangaChapter> {
        if (english.isEmpty()) return japanese
        val covered = japanese.mapNotNullTo(HashSet()) { it.number }
        return (japanese + english.filter { it.number !in covered }).sortedForReading()
    }

    /** Забыть найденное: пользователь сменил привязку, прежний вывод мог устареть. */
    suspend fun forget(animeId: String) {
        matches.update { it - animeId }
    }

    private suspend fun discover(request: TailRequest): JaMatch? {
        val native = natives.resolve(request.animeId, request.queries)
        Log.i(TAG, "native title for ${request.animeId} (queries=${request.queries}): ${native?.titles} id=${native?.aniListId}")
        if (native == null) {
            matches.update { it + (request.animeId to JaMatch(checkedAt = clock())) }
            return null
        }
        health.ensureLoaded()
        val ordered = sources.sortedByDescending { score(it.id.value) }
        val gate = Semaphore(SEARCH_CONCURRENCY)
        val hits = coroutineScope {
            ordered.map { source ->
                async { gate.withPermit { searchOne(source, native) } }
            }.awaitAll()
        }
        // Все сайты, где нашлась серия, в порядке надёжности: главы берём со всех, а при совпадении номера - у более надёжного.
        val found = hits.filterNotNull().map { (source, item) -> JaHit(source.id.value, item.key, item.title) }
        val first = found.firstOrNull()
        Log.i(TAG, "japanese sites with ${request.animeId}: ${found.map { it.sourceId }}")
        val match = JaMatch(
            sourceId = first?.sourceId,
            seriesKey = first?.seriesKey,
            title = first?.title,
            checkedAt = clock(),
            hits = found,
        )
        matches.update { it + (request.animeId to match) }
        return match.takeIf { it.found }
    }

    private suspend fun searchOne(source: VetroMangaSource, native: NativeEntry): Pair<VetroMangaSource, MangaItem>? {
        val started = clock()
        for (title in native.titles) {
            val page = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                runCatching { source.search(title, 1) }
                    .onFailure { Log.w(TAG, "${source.name}: search failed: ${it.message}") }
                    .getOrNull()
            }
            if (page == null) {
                record(source, ok = false, started)
                return null
            }
            val item = page.items.firstOrNull {
                JaTitles.same(it.title, title) && JaTitles.authorMatches(it.author, native.authors)
            }
            if (item != null) {
                record(source, ok = true, started)
                return source to item
            }
        }
        record(source, ok = true, started)
        return null
    }

    /**
     * Главы со ВСЕХ сайтов, где нашлась серия: у одного бесплатна только первая глава, у другого
     * вся - выбирать "лучший" сайт заранее нельзя. Номер, который есть на нескольких, берётся у сайта,
     * стоящего в списке раньше (он надёжнее: список упорядочен при поиске).
     */
    private suspend fun chaptersAfter(match: JaMatch, lastKnown: Double): List<MangaChapter> {
        val perSite = match.allHits().mapNotNull { hit ->
            val source = sourceById(hit.sourceId) ?: return@mapNotNull null
            fetchAfter(source, hit, lastKnown)
        }
        val byNumber = LinkedHashMap<Double, MangaChapter>()
        for (chapters in perSite) for (chapter in chapters) byNumber.putIfAbsent(chapter.number!!, chapter)
        return byNumber.values.toList().sortedForReading()
    }

    private suspend fun fetchAfter(source: VetroMangaSource, hit: JaHit, lastKnown: Double): List<MangaChapter> {
        val memoKey = "${source.id.value}|${hit.seriesKey}"
        val now = clock()
        val cachedAll = memo[memoKey]?.takeIf { now - it.first < MEMO_TTL_MS }?.second
        val all = cachedAll ?: run {
            val started = clock()
            val fetched = withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) {
                runCatching { source.chapters(MangaItem(source.id, hit.seriesKey, hit.title, languages = listOf("ja"))) }
                    .onFailure { Log.w(TAG, "${source.name}: chapters failed: ${it.message}") }
                    .getOrNull()
            }
            health.ensureLoaded()
            record(source, ok = fetched != null, started)
            if (fetched != null) memo[memoKey] = now to fetched
            fetched.orEmpty()
        }
        val tail = all
            .filter { chapter -> !chapter.paid && chapter.number != null && chapter.number!! > lastKnown }
            .groupBy { it.number }
            .map { (_, versions) -> versions.maxBy { it.publishedAt } }
            .sortedForReading()
        if (tail.isEmpty() || !isReadable(source, memoKey, tail)) return emptyList()
        return tail
    }

    /**
     * Не всё, что сайт числит "бесплатным", открывается без входа в аккаунт: акции вроде "бесплатно
     * на время" требуют входа, и страниц такие главы без него не отдают. Входить за пользователя или
     * обходить это мы не станем, поэтому пробуем открыть первую и последнюю главу хвоста и, если обе
     * закрыты, хвост этого сайта не показываем - иначе ридер падал бы на каждой главе.
     */
    private suspend fun isReadable(source: VetroMangaSource, memoKey: String, tail: List<MangaChapter>): Boolean {
        val now = clock()
        readable[memoKey]?.takeIf { now - it.first < MEMO_TTL_MS }?.let { return it.second }
        val samples = listOf(tail.first(), tail.last()).distinctBy { it.key }
        val ok = samples.any { sample ->
            withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) {
                runCatching { source.pages(sample).isNotEmpty() }.getOrDefault(false)
            } == true
        }
        readable[memoKey] = now to ok
        return ok
    }

    private fun score(id: String): Double {
        val h = health[id]
        val ok = h?.ok ?: 0
        val fail = h?.fail ?: 0
        val rate = (ok + 1.0) / (ok + fail + 2.0)
        val latency = h?.latencyMs?.takeIf { it > 0 } ?: NEUTRAL_LATENCY_MS
        return rate * 100.0 - latency / 100.0
    }

    private suspend fun record(source: VetroMangaSource, ok: Boolean, startedAt: Long) {
        val spent = (clock() - startedAt).coerceAtLeast(1L)
        health.update { map ->
            val old = map[source.id.value] ?: JaHealth()
            val latency = if (old.latencyMs == 0L) spent else (old.latencyMs * 7 + spent * 3) / 10
            map + (source.id.value to JaHealth(
                ok = old.ok + if (ok) 1 else 0,
                fail = old.fail + if (ok) 0 else 1,
                latencyMs = latency,
            ))
        }
    }

    private companion object {
        const val TAG = "JaTail"
        const val SEARCH_CONCURRENCY = 4
        const val SEARCH_TIMEOUT_MS = 12_000L
        const val CHAPTERS_TIMEOUT_MS = 20_000L
        const val MEMO_TTL_MS = 10L * 60 * 1000
        const val MISS_TTL_MS = 12L * 3600 * 1000
        const val NEUTRAL_LATENCY_MS = 800L
    }
}
