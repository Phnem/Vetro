package com.example.myapplication.manga.domain

import android.content.Context
import android.graphics.Canvas
import android.util.Log
import coil3.Image
import coil3.ImageLoader
import com.example.myapplication.manga.ui.MangaImageLoader
import com.example.myapplication.manga.ui.coilData
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import com.example.myapplication.manga.download.MangaPageResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Догрузка страниц вперёд в дисковый кэш Coil (порт идеи Kotatsu `MangaPrefetchService`).
 *
 * Смысл в том, что страница манги весит сотни килобайт и по мобильной сети приезжает заметно
 * дольше, чем занимает свайп: без предзагрузки каждая следующая страница встречает читателя
 * спиннером. К моменту показа страница должна уже лежать на диске.
 *
 * Три сознательных ограничения, без которых предзагрузка вредит больше, чем помогает:
 *  1. **Последовательно, по одной.** Пачка параллельных запросов отбирает соединения у страницы,
 *     которую читатель смотрит прямо сейчас, — то есть ускоряет будущее за счёт настоящего.
 *  2. **Только на диск.** Память Coil трогать нельзя: страницы огромные, и десяток предзагруженных
 *     вытеснил бы из кэша ровно те, что сейчас на экране. Соседнюю страницу и так держит сам
 *     пейджер (`beyondViewportPageCount`), здесь речь про запас подальше.
 *  3. **Без декодирования.** Prefetch'у нужны байты, а не битмап: распаковывать страницу, чтобы
 *     тут же её выбросить, — чистая трата CPU и памяти (см. [SkipDecoding]).
 */
class MangaPagePrefetcher(
    private val context: Context,
    private val pageResolver: MangaPageResolver,
) {

    /** Тот же самый loader, что рисует страницы, — иначе прогрев попал бы в чужой кэш. */
    private val imageLoader: ImageLoader
        get() = MangaImageLoader.get(context)

    /**
     * Прогреть окрестности [fromPage]: несколько страниц вперёд по текущей главе, а на её хвосте —
     * ещё и начало [nextChapter], чтобы переход «следующая глава» не начинался с пустого экрана.
     *
     * Отменяемо в любой точке: вызывающий перезапускает префетч на каждой смене страницы.
     */
    suspend fun prefetch(
        pages: List<MangaPage>,
        fromPage: Int,
        nextChapter: MangaChapter?,
    ) {
        for (page in pages.drop(fromPage + 1).take(PAGES_AHEAD)) {
            coroutineContext.ensureActive()
            warm(page)
        }
        if (nextChapter == null || fromPage < pages.size - NEXT_CHAPTER_LEAD) return
        coroutineContext.ensureActive()
        // Оглавление следующей главы стоит одного запроса к источнику — но только у самого конца
        // текущей, иначе мы дёргали бы источник на каждой открытой главе впустую.
        val nextPages = runCatching { pageResolver.pages(nextChapter) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "next chapter pages failed for ${nextChapter.key}", error)
                return
            }
        for (page in nextPages.take(NEXT_CHAPTER_PAGES)) {
            coroutineContext.ensureActive()
            warm(page)
        }
    }

    /** Одна страница в дисковый кэш. Любая неудача здесь безобидна: страницу докачает сам ридер. */
    private suspend fun warm(page: MangaPage) {
        // Скачанная глава уже лежит файлом — гонять её через кэш картинок незачем.
        if (page.isLocal) return
        val request = ImageRequest.Builder(context)
            .data(page.coilData())
            .apply {
                // Те же заголовки, что у настоящей загрузки: без Referer часть источников отдаёт
                // 403, и прогрев молча складывал бы в кэш ошибки.
                if (page.headers.isNotEmpty()) {
                    val headers = NetworkHeaders.Builder().apply {
                        page.headers.forEach { (name, value) -> add(name, value) }
                    }.build()
                    httpHeaders(headers)
                }
            }
            .memoryCachePolicy(CachePolicy.DISABLED)
            .decoderFactory(SkipDecoding)
            .build()
        runCatching { imageLoader.execute(request) }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "prefetch failed for ${page.url}", error)
        }
    }

    /**
     * Декодер-пустышка: сетевой fetcher Coil кладёт тело ответа в дисковый кэш и коммитит его
     * ДО того, как дело доходит до декодирования, — значит после fetch'а работа префетча уже
     * сделана, и распаковывать пиксели незачем.
     *
     * Ставится на конкретный запрос (`ImageRequest.decoderFactory`), а не в `ImageLoader`, поэтому
     * обычная отрисовка страниц идёт прежним путём через [com.example.myapplication.manga.ui.RegionBitmapDecoder].
     */
    private object SkipDecoding : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = Decoder { DecodeResult(image = BlankImage, isSampled = false) }

        override fun equals(other: Any?) = other is SkipDecoding

        override fun hashCode() = javaClass.hashCode()
    }

    /** Формальный результат для [SkipDecoding]: его никто не рисует, таргета у запроса нет. */
    private object BlankImage : Image {
        override val width: Int = 1
        override val height: Int = 1
        override val size: Long = 0L
        override val shareable: Boolean = true
        override fun draw(canvas: Canvas) = Unit
    }

    private companion object {
        const val TAG = "MangaPagePrefetcher"

        /**
         * Сколько страниц держать наготове. Дисковый кэш картинок общий на всё приложение и
         * невелик (50 МБ), так что запас должен покрывать пару свайпов, а не главу целиком.
         */
        const val PAGES_AHEAD = 4

        /** За сколько страниц до конца главы начинать тянуть следующую. */
        const val NEXT_CHAPTER_LEAD = 3

        /** Начала следующей главы хватает: дальше её догреет обычный префетч уже изнутри. */
        const val NEXT_CHAPTER_PAGES = 2
    }
}
