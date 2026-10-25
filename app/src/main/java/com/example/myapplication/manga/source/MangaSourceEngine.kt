package com.example.myapplication.manga.source

import android.util.Log
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.VetroMangaSource
import com.example.myapplication.manga.domain.sortedForReading
import com.example.myapplication.utils.gatherWithGrace
import kotlinx.coroutines.withTimeoutOrNull

/** Результат поиска по одному источнику: пустой список значит «источник ответил и ничего не нашёл». */
data class MangaSourceResults(
    val sourceId: MangaSourceId,
    val sourceName: String,
    val items: List<MangaItem>,
)

/**
 * Реестр источников манги + вся защита от их капризов: таймаут и ОДИН retry на сетевой вызов.
 *
 * Ровно как у [com.example.myapplication.media.source.SourceEngine] для аниме: сам источник не
 * обязан быть аккуратным, поэтому висящий или упавший источник не должен ни ронять экран, ни
 * блокировать остальные — упавший просто выпадает из выдачи.
 */
class MangaSourceEngine(
    private val sources: List<VetroMangaSource>,
    /**
     * Японские источники: главы с них добавляет японская цепочка ([com.example.myapplication.manga.ja.JaTailResolver]),
     * а страницы открывает этот же движок. В выборе источника и в поиске их нет - [sources] не меняется.
     */
    private val japaneseSources: List<VetroMangaSource> = emptyList(),
) {
    fun sources(): List<VetroMangaSource> = sources

    fun sourceById(id: MangaSourceId): VetroMangaSource? =
        sources.firstOrNull { it.id == id } ?: japaneseSources.firstOrNull { it.id == id }

    /**
     * Поиск по всем источникам параллельно — для экрана привязки тайтл↔источник (§8 плана). Как в
     * поиске по каталогам: после первого непустого ответа остальным даётся [SEARCH_GRACE_MS], и
     * выдача не ждёт самый медленный источник.
     */
    suspend fun searchAll(query: String): List<MangaSourceResults> {
        if (query.isBlank()) return emptyList()
        return gatherWithGrace(
            blocks = sources.map { source ->
                suspend {
                    val items = call(source.name, "search") { source.search(query, page = 1) }
                        ?.items
                        .orEmpty()
                    if (items.isEmpty()) emptyList() else listOf(MangaSourceResults(source.id, source.name, items.rankedFor(query)))
                }
            },
            hardTimeoutMs = DEFAULT_TIMEOUT_MS,
            graceMs = SEARCH_GRACE_MS,
        ).flatten()
    }

    suspend fun search(sourceId: MangaSourceId, query: String, page: Int = 1): List<MangaItem> {
        val source = sourceById(sourceId) ?: return emptyList()
        return call(source.name, "search") { source.search(query, page) }?.items.orEmpty()
    }

    suspend fun chapters(manga: MangaItem): List<MangaChapter> {
        val source = sourceById(manga.sourceId) ?: return emptyList()
        return call(source.name, "chapters", CHAPTERS_TIMEOUT_MS) { source.chapters(manga) }
            .orEmpty()
            .sortedForReading()
    }

    /** Описание тайтла у источника; null - источник не ответил. */
    suspend fun details(manga: MangaItem): MangaDetails? {
        val source = sourceById(manga.sourceId) ?: return null
        return call(source.name, "details") { source.details(manga) }
    }

    suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        val source = sourceById(chapter.sourceId) ?: return emptyList()
        return call(source.name, "pages") { source.pages(chapter) }.orEmpty()
    }

    /**
     * Насколько результат поиска похож на запрос. Автоматически ничего не привязываем (§8) —
     * ранжирование нужно, только чтобы первым в списке выбора стоял правдоподобный вариант.
     */
    private fun List<MangaItem>.rankedFor(query: String): List<MangaItem> {
        val needle = query.normalizedTitle()
        return sortedByDescending { item ->
            val title = item.title.normalizedTitle()
            val alt = item.altTitle?.normalizedTitle().orEmpty()
            when {
                title == needle || alt == needle -> 100
                title.startsWith(needle) || alt.startsWith(needle) -> 70
                title.contains(needle) || alt.contains(needle) -> 50
                needle.contains(title) && title.isNotEmpty() -> 30
                else -> 0
            }
        }
    }

    private suspend fun <T> call(
        sourceName: String,
        op: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: suspend () -> T,
    ): T? {
        repeat(ATTEMPTS) { attempt ->
            val result = runCatching { withTimeoutOrNull(timeoutMs) { block() } }
                .onFailure { error ->
                    Log.w(TAG, "$sourceName.$op failed (attempt ${attempt + 1})", error)
                }
                .getOrNull()
            if (result != null) return result
            if (attempt == ATTEMPTS - 1) {
                Log.w(TAG, "$sourceName.$op gave up after $ATTEMPTS attempts")
            }
        }
        return null
    }

    private companion object {
        const val TAG = "MangaSourceEngine"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        /** Сколько ждём остальных источников поиска после первого непустого ответа. */
        const val SEARCH_GRACE_MS = 4_000L
        /** Список глав у длинных тайтлов — это несколько страниц фида по 500 записей. */
        const val CHAPTERS_TIMEOUT_MS = 40_000L
        /** Один повтор: сетевой сбой лечит, а мёртвый источник не превращает в минуту ожидания. */
        const val ATTEMPTS = 2
    }
}

private fun String.normalizedTitle(): String =
    lowercase().replace(NON_ALNUM, " ").trim().replace(SPACES, " ")

private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val SPACES = Regex("\\s+")
