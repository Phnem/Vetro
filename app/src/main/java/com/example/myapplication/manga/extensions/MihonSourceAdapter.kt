package com.example.myapplication.manga.extensions

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSearchPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.MangaStatus
import com.example.myapplication.manga.domain.VetroMangaSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Источник из расширения Mihon за границей [VetroMangaSource]: ридер, привязки и прогресс не знают,
 * что главы пришли из чужого APK.
 *
 * Ключи — `url` из моделей расширения (путь без домена или полный адрес, как решит источник):
 * тайтл и глава восстанавливаются обратно в [SManga]/[SChapter] по этому же url.
 * Id — `mihon:<id источника>`: id у Mihon стабилен между версиями расширения, привязки переживают
 * обновление.
 */
internal class MihonSourceAdapter(
    private val source: CatalogueSource,
    /** Расширение отдаёт один сайт на нескольких языках — тогда язык виден в имени. */
    showLanguage: Boolean,
) : VetroMangaSource {

    override val id = MangaSourceId("$ID_PREFIX${source.id}")
    override val name = if (showLanguage) "${source.name} (${source.lang.uppercase()})" else source.name
    override val language: String = source.lang

    override suspend fun search(query: String, page: Int): MangaSearchPage = io {
        val result = source.getSearchManga(page, query, source.getFilterList())
        MangaSearchPage(result.mangas.mapNotNull { it.toItem() }, result.hasNextPage)
    }

    override suspend fun details(manga: MangaItem): MangaDetails = io {
        val update = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = true, fetchChapters = false)
        val sManga = update.manga
        MangaDetails(
            item = sManga.toItem(fallback = manga) ?: manga,
            description = sManga.description?.takeIf { it.isNotBlank() },
            tags = sManga.genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            authors = listOfNotNull(sManga.author, sManga.artist).flatMap { it.split(',') }
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    override suspend fun chapters(manga: MangaItem): List<MangaChapter> = io {
        val update = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true)
        update.chapters.mapNotNull { chapter ->
            if (chapter.url.isBlank()) return@mapNotNull null
            MangaChapter(
                sourceId = id,
                mangaKey = manga.key,
                key = chapter.url,
                // Float -> строка -> Double: 10.1f иначе превращается в 10.100000381469727.
                number = chapter.chapter_number.takeIf { it >= 0f }?.toString()?.toDoubleOrNull(),
                title = chapter.name.takeIf { it.isNotBlank() },
                language = source.lang,
                scanlator = chapter.scanlator?.takeIf { it.isNotBlank() },
                publishedAt = chapter.date_upload,
            )
        }
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = io {
        val sChapter = SChapter.create().apply {
            url = chapter.key
            name = chapter.title.orEmpty()
        }
        val http = source as? HttpSource
        source.getPageList(sChapter).mapIndexedNotNull { position, page ->
            runCatching {
                if (page.imageUrl.isNullOrBlank() && http != null && page.url.isNotBlank()) {
                    page.imageUrl = http.getImageUrl(page)
                }
                page.toMangaPage(position, http)
            }.getOrNull()
        }
    }

    /**
     * Страница для загрузчика Vetro: адрес и заголовки берём из `imageRequest` источника — так
     * доходят Referer и куки-зависимые заголовки, которые сайт требует для картинок.
     *
     * VETRO-TAIL[mihon-image-client]: перехватчики клиента источника (расшифровка перемешанных
     * страниц, подписанные ссылки) так не работают — для них нужен свой фетчер Coil, который
     * качает картинку через `source.client`.
     */
    private fun Page.toMangaPage(position: Int, http: HttpSource?): MangaPage? {
        val image = imageUrl?.takeIf { it.isNotBlank() } ?: return null
        if (http == null) return MangaPage(index = position, url = image)
        val request = http.hostImageRequest(this)
        return MangaPage(
            index = position,
            url = request.url.toString(),
            headers = request.headers.toMap(),
        )
    }

    private fun SManga.toItem(fallback: MangaItem? = null): MangaItem? {
        val key = url.ifBlank { fallback?.key.orEmpty() }
        val title = title.ifBlank { fallback?.title.orEmpty() }
        if (key.isBlank() || title.isBlank()) return null
        return MangaItem(
            sourceId = id,
            key = key,
            title = title,
            coverUrl = thumbnail_url?.takeIf { it.startsWith("http") } ?: fallback?.coverUrl,
            status = when (status) {
                SManga.ONGOING -> MangaStatus.Ongoing
                SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaStatus.Completed
                SManga.ON_HIATUS -> MangaStatus.Hiatus
                SManga.CANCELLED -> MangaStatus.Cancelled
                else -> MangaStatus.Unknown
            },
            languages = listOf(source.lang),
            author = author?.takeIf { it.isNotBlank() },
        )
    }

    private fun MangaItem.toSManga(): SManga = SManga.create().also {
        it.url = key
        it.title = title
        it.thumbnail_url = coverUrl
    }

    /** Расширения местами делают блокирующие вызовы — не на главном потоке. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val ID_PREFIX = "mihon:"
    }
}
