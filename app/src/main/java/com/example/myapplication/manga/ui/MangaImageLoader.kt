package com.example.myapplication.manga.ui

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath

/**
 * Загрузчик картинок для страниц манги — отдельный от общего.
 *
 * Зачем отдельный:
 *  - **Свой дисковый кэш.** Одна глава — это десятки мегабайт страниц, и в общем кэше на 50 МБ
 *    она вытесняла обложки коллекции: после чтения главная сетка качала постеры заново.
 *  - **Регион-декодер только здесь.** [RegionBitmapDecoder.Factory] подглядывает в заголовок
 *    КАЖДОЙ картинки, чтобы решить, не вебтун ли это. Для обложек и постеров это лишний разбор
 *    на каждом декодировании — гигантских полос среди них не бывает.
 *
 * Кэш в памяти общий с основным загрузчиком (`newBuilder`): бюджет памяти на картинки один.
 */
object MangaImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): ImageLoader =
        SingletonImageLoader.get(context).newBuilder()
            .components {
                // Вебтун-страницы бывают в десятки тысяч пикселей высотой: штатный декодер на них
                // либо ловит OOM, либо упирается в лимит текстуры. Фабрика сама решает,
                // вмешиваться ли, — обычные страницы идут штатным путём.
                add(RegionBitmapDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("manga_page_cache").toOkioPath())
                    .maxSizeBytes(MANGA_DISK_CACHE_BYTES)
                    .build()
            }
            .build()

    /** Несколько глав вперёд и назад — с запасом для префетча следующей главы. */
    private const val MANGA_DISK_CACHE_BYTES = 128L * 1024 * 1024
}
