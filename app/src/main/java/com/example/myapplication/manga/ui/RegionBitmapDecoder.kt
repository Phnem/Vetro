package com.example.myapplication.manga.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.request.colorSpace
import coil3.request.maxBitmapSize
import coil3.request.premultipliedAlpha
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.isOriginal
import coil3.size.pxOrElse
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Декодер для «бесконечных» вебтун-страниц (десятки тысяч пикселей по высоте).
 *
 * Штатный [coil3.decode.BitmapFactoryDecoder] такие картинки не тянет по двум причинам:
 *  1) в вебтун-режиме высота цели не ограничена (`Dimension.Undefined`), поэтому Coil не
 *     обрезает её своим `maxBitmapSize` — и пытается выделить один битмап на 60+ МБ (OOM);
 *  2) даже если память нашлась, HWUI не может залить в GPU битмап выше `GL_MAX_TEXTURE_SIZE` —
 *     страница просто не рисуется, остаётся пустое место.
 *
 * Решение — чанковое декодирование: [BitmapRegionDecoder] режет исходник на горизонтальные
 * полосы, каждая из которых заведомо влезает в текстуру, а наружу отдаётся [TiledImage] —
 * составной [Image], который рисует полосы стопкой. Coil 3 умеет работать с произвольным
 * [Image] (`coil3.compose.ImagePainter` берёт intrinsic size из width/height и сам масштабирует
 * канву), так что `AsyncImage` с `ContentScale.FillWidth` продолжает работать как раньше.
 *
 * Порт логики Kotatsu (`org.koitharu.kotatsu.core.image.RegionBitmapDecoder`) под Coil 3.3.0.
 */
class RegionBitmapDecoder(
    private val fetchResult: SourceFetchResult,
    private val options: Options,
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val srcMimeType: String?,
) : Decoder {

    override suspend fun decode(): DecodeResult? = runInterruptible { decodeBlocking() }

    private fun decodeBlocking(): DecodeResult? {
        // Регион-декодеру нужен произвольный доступ. Если источник уже лежит файлом (типичный
        // случай — дисковый кэш Coil), читаем файл; иначе подглядываем в поток через peek(),
        // чтобы НЕ израсходовать ImageSource: он ещё понадобится запасному декодеру.
        val filePath = runCatching { fetchResult.source.fileOrNull()?.toString() }.getOrNull()
        val regionDecoder = if (filePath != null) {
            createRegionDecoder(filePath)
        } else {
            fetchResult.source.source().peek().inputStream().use { createRegionDecoder(it) }
        } ?: return null // фолбэк: null заставляет Coil перейти к следующей Decoder.Factory

        return try {
            val config = resolveConfig()
            val sampleSize = resolveSampleSize(config)
            val tiles = decodeTiles(regionDecoder, sampleSize, config) ?: return null
            DecodeResult(
                image = tiles,
                isSampled = sampleSize > 1,
            )
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // decodeRegion любит кидать IllegalArgumentException на нестандартных форматах —
            // это не повод ронять страницу, пусть попробует штатный декодер.
            null
        } catch (e: OutOfMemoryError) {
            // Лучше отдать управление штатному декодеру, чем уронить весь процесс.
            null
        } finally {
            regionDecoder.recycle()
        }
    }

    /** Порезать исходник на полосы, каждая из которых гарантированно влезает в текстуру. */
    private fun decodeTiles(
        regionDecoder: BitmapRegionDecoder,
        sampleSize: Int,
        config: Bitmap.Config,
    ): Image? {
        val bitmapOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inMutable = false
            inPreferredConfig = config
            inPremultiplied = options.premultipliedAlpha
            if (options.colorSpace != null) {
                inPreferredColorSpace = options.colorSpace
            }
        }
        // Высота полосы в исходных пикселях — так, чтобы после сэмплинга получилось ровно
        // не больше лимита текстуры.
        val tileSrcHeight = (MAX_TEXTURE_SIZE.toLong() * sampleSize)
            .coerceAtMost(srcHeight.toLong())
            .toInt()
            .coerceAtLeast(1)

        val tiles = ArrayList<Bitmap>()
        var top = 0
        try {
            while (top < srcHeight) {
                val bottom = (top + tileSrcHeight).coerceAtMost(srcHeight)
                val bitmap = regionDecoder.decodeRegion(Rect(0, top, srcWidth, bottom), bitmapOptions)
                if (bitmap == null) {
                    tiles.forEach { it.recycle() }
                    return null
                }
                // Плотность гасим: иначе Canvas.drawBitmap может домасштабировать полосу под
                // свою density, и стык между полосами уедет на пиксель-другой.
                bitmap.density = Bitmap.DENSITY_NONE
                tiles += bitmap
                top = bottom
            }
        } catch (e: Throwable) {
            tiles.forEach { it.recycle() }
            throw e
        }
        if (tiles.isEmpty()) return null
        // Одна полоса — обычный BitmapImage, лишняя обёртка не нужна.
        return if (tiles.size == 1) tiles[0].asImage() else TiledImage(tiles)
    }

    /** Конфиг пикселей по правилам Coil, но никогда не HARDWARE: полосы мы рисуем сами. */
    private fun resolveConfig(): Bitmap.Config {
        var config = options.bitmapConfig
        if (config == Bitmap.Config.HARDWARE) {
            config = Bitmap.Config.ARGB_8888
        }
        if (options.allowRgb565 && config == Bitmap.Config.ARGB_8888 && srcMimeType == MIME_TYPE_JPEG) {
            config = Bitmap.Config.RGB_565
        }
        return config
    }

    /**
     * Степень двойки, до которой ужимаем исходник.
     *
     * Стартуем с того, что посчитал бы сам Coil (чтобы не терять качество там, где его хватает),
     * а дальше добираем ровно до тех пор, пока страница не влезет в бюджет памяти и по ширине —
     * в текстуру (по вертикали нас спасают полосы, по горизонтали резать нечем).
     *
     * inScaled/inDensity намеренно не используем: точное «дотягивание» до целевого размера дало бы
     * дробный масштаб и швы между полосами, а финальное масштабирование всё равно делает Compose.
     */
    private fun resolveSampleSize(config: Bitmap.Config): Int {
        val (dstWidth, dstHeight) = computeDstSize(srcWidth, srcHeight, options)

        var sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )
        while (sampleSize < MAX_SAMPLE_SIZE) {
            val outWidth = ceilDiv(srcWidth, sampleSize)
            val outHeight = ceilDiv(srcHeight, sampleSize)
            val fitsMemory = outWidth.toLong() * outHeight * config.bytesPerPixel() <= maxImageBytes
            if (fitsMemory && outWidth <= MAX_TEXTURE_SIZE) break
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Составной [Image] из горизонтальных полос. Каждая полоса — самостоятельный битмап в
     * пределах лимита текстуры, поэтому HWUI рисует их без проблем, а логически это одна
     * картинка высотой в сумму полос.
     */
    private class TiledImage(private val tiles: List<Bitmap>) : Image {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        override val width: Int = tiles.maxOf { it.width }

        override val height: Int = tiles.sumOf { it.height }

        override val size: Long = tiles.sumOf { it.allocationByteCount.toLong() }

        // Полосы иммутабельны, картинку можно шарить между таргетами.
        override val shareable: Boolean = true

        override fun draw(canvas: android.graphics.Canvas) {
            var top = 0f
            for (tile in tiles) {
                canvas.drawBitmap(tile, 0f, top, paint)
                top += tile.height
            }
        }
    }

    class Factory : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            // Анимации регион-декодер не умеет в принципе — даже не пробуем.
            if (result.mimeType == MIME_TYPE_GIF) return null

            // Читаем только заголовок и только через peek(): ImageSource обязан остаться нетронутым,
            // иначе следующие декодеры получат пустой поток.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                result.source.source().peek().inputStream().use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            } catch (e: Throwable) {
                return null
            }
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return null

            if (!needsTiling(srcWidth, srcHeight, options)) return null

            return RegionBitmapDecoder(
                fetchResult = result,
                options = options,
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                srcMimeType = bounds.outMimeType ?: result.mimeType,
            )
        }

        /**
         * Вмешиваемся только там, где штатный путь ломается: считаем, какой битмап выдал бы
         * `BitmapFactoryDecoder`, и берём страницу себе, если он не влезет в текстуру или в
         * бюджет памяти. Обычные страницы, обложки и аватарки уходят в штатный декодер.
         */
        private fun needsTiling(srcWidth: Int, srcHeight: Int, options: Options): Boolean {
            val (dstWidth, dstHeight) = computeDstSize(srcWidth, srcHeight, options)

            val sampleSize = DecodeUtils.calculateInSampleSize(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = dstWidth,
                dstHeight = dstHeight,
                scale = options.scale,
            )
            var multiplier = DecodeUtils.computeSizeMultiplier(
                srcWidth = srcWidth / sampleSize.toDouble(),
                srcHeight = srcHeight / sampleSize.toDouble(),
                dstWidth = dstWidth.toDouble(),
                dstHeight = dstHeight.toDouble(),
                scale = options.scale,
            )
            if (options.precision == Precision.INEXACT) {
                multiplier = multiplier.coerceAtMost(1.0)
            }
            val outWidth = (srcWidth / sampleSize * multiplier).roundToInt().coerceAtLeast(1)
            val outHeight = (srcHeight / sampleSize * multiplier).roundToInt().coerceAtLeast(1)
            val outBytes = outWidth.toLong() * outHeight * options.bitmapConfig.bytesPerPixel()

            // Отсечка по консервативному минимуму: отрабатывает для всей обычной графики
            // приложения — обложки и обычные страницы уходят в штатный декодер как раньше.
            return outWidth > MAX_TEXTURE_SIZE ||
                outHeight > MAX_TEXTURE_SIZE ||
                outBytes > maxImageBytes
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    private companion object {

        const val MIME_TYPE_JPEG = "image/jpeg"
        const val MIME_TYPE_GIF = "image/gif"

        /** Дальше ужимать бессмысленно — страница превратится в кашу. */
        const val MAX_SAMPLE_SIZE = 32

        /**
         * Нижняя граница GL_MAX_TEXTURE_SIZE на всём парке Android-устройств.
         *
         * Реальный лимит устройства (обычно 4096…16384) намеренно не спрашиваем: узнать его можно
         * только у живого EGL-контекста, а поднимать его на потоке декодирования, по дисплею,
         * общему с HWUI, — куда больший риск, чем выигрыш. Резать по 4096 безопасно везде, лишняя
         * полоса-другая на страницу ничего не стоит.
         */
        const val MAX_TEXTURE_SIZE = 4096

        /**
         * Потолок на одну страницу. В LazyColumn вебтуна одновременно живёт несколько страниц,
         * поэтому берём лишь долю кучи, а не «сколько влезет».
         */
        val maxImageBytes: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(24L * 1024 * 1024, 72L * 1024 * 1024)
        }

        fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

        /**
         * Повторяет `DecodeUtils.computeDstSize` (он возвращает экспериментальный `IntPair`,
         * а тащить сюда opt-in ради двух чисел не хочется).
         *
         * Ключевой момент, ради которого этот тикет вообще существует: неопределённое измерение
         * превращается в Int.MAX_VALUE и НЕ обрезается `maxBitmapSize` — именно так вебтун
         * проскакивает мимо ограничителя Coil.
         */
        fun computeDstSize(srcWidth: Int, srcHeight: Int, options: Options): Pair<Int, Int> {
            val targetSize = options.size
            var dstWidth: Int
            var dstHeight: Int
            if (targetSize.isOriginal) {
                dstWidth = srcWidth
                dstHeight = srcHeight
            } else {
                dstWidth = targetSize.width.toPx(options.scale)
                dstHeight = targetSize.height.toPx(options.scale)
            }
            val maxSize = options.maxBitmapSize
            val maxWidth = maxSize.width
            if (maxWidth is Dimension.Pixels && !dstWidth.isMinOrMax()) {
                dstWidth = dstWidth.coerceAtMost(maxWidth.px)
            }
            val maxHeight = maxSize.height
            if (maxHeight is Dimension.Pixels && !dstHeight.isMinOrMax()) {
                dstHeight = dstHeight.coerceAtMost(maxHeight.px)
            }
            return dstWidth to dstHeight
        }

        fun Dimension.toPx(scale: Scale): Int = pxOrElse {
            when (scale) {
                Scale.FILL -> Int.MIN_VALUE
                Scale.FIT -> Int.MAX_VALUE
            }
        }

        fun Int.isMinOrMax(): Boolean = this == Int.MIN_VALUE || this == Int.MAX_VALUE

        fun Bitmap.Config.bytesPerPixel(): Int = when (this) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.RGBA_F16 -> 8
            else -> 4
        }

        /**
         * `BitmapRegionDecoder.newInstance` без `isShareable` появился только в API 31,
         * старая перегрузка с этого же API помечена deprecated — отсюда развилка.
         */
        fun createRegionDecoder(path: String): BitmapRegionDecoder? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(path, false)
            }
        } catch (e: IOException) {
            null
        }

        fun createRegionDecoder(stream: InputStream): BitmapRegionDecoder? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        } catch (e: IOException) {
            null
        }

    }
}
