package com.example.myapplication.micromlintegration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.myapplication.microml.MicroMlEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Узнаёт «стоковую» картинку вместо обложки (у Shikimori — девочка с поднятой рукой и «404 not found») через модель Catalog.
 * Работает только пока включён тумблер «Разбор файлов и жанров»; без модели, при выключенном сценарии и при любом сбое отвечает «нет»,
 * то есть обложка остаётся как есть.
 */
class StockCoverDetector(private val engine: MicroMlEngine?) {

    /** Проверять имеет смысл: сценарий включён и модель загрузилась. */
    suspend fun isEnabled(): Boolean {
        val e = engine ?: return false
        return e.isActive(MicroMlEngine.Feature.CATALOG_COVER_PLACEHOLDER) && e.coverPlaceholder() != null
    }

    suspend fun isStock(bitmap: Bitmap): Boolean = withContext(Dispatchers.Default) {
        var source = bitmap
        // Огромные оригиналы уменьшаем: признаки считаются по сетке 16×16, лишние миллионы пикселей ничего не добавляют.
        var scaled: Bitmap? = null
        while (source.width.toLong() * source.height > MAX_PIXELS) {
            val next = Bitmap.createScaledBitmap(source, source.width / 2, source.height / 2, true)
            scaled?.recycle()
            scaled = next
            source = next
        }
        try {
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            MicroMlBridge.isStockCover(engine, pixels, w, h)
        } finally {
            scaled?.recycle()
        }
    }

    /** Сохранённый файл картинки: читается уменьшенным (до ~500 px по длинной стороне), целиком в память не грузится. */
    suspend fun isStockFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext false
        try {
            isStock(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_PIXELS = 2_500_000L
        const val TARGET_SIDE = 500
    }
}

/** Скачанная картинка оказалась стоковой заглушкой: сохранять её нельзя, нужен другой источник. */
class StockCoverException : Exception("The image is a stock placeholder, not a cover")
