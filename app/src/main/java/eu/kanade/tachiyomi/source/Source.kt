package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

/**
 * Источник манги из расширения Mihon (tachiyomix 1.6, только suspend-API, без RxJava).
 *
 * Пакет `eu.kanade.tachiyomi` — хост-сторона контракта: расширения собраны против стабов
 * `keiyoushi/extensions-lib` (compileOnly) и при загрузке находят настоящие классы здесь, в
 * загрузчике Vetro. Поэтому имена, сигнатуры и значения по умолчанию менять нельзя, а R8 эти
 * пакеты не трогает (см. proguard-rules.pro). Мост в домен Vetro — `manga.extensions.MihonSourceAdapter`.
 */
interface Source {
    val id: Long
    val name: String
    val supportsLatest: Boolean

    fun getFilterList(): FilterList = FilterList()

    suspend fun getPopularManga(page: Int): MangasPage

    suspend fun getLatestUpdates(page: Int): MangasPage

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    /** Описание и/или главы за один вызов (раньше fetchMangaDetails + fetchChapterList). */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate

    suspend fun getPageList(chapter: SChapter): List<Page>
}
