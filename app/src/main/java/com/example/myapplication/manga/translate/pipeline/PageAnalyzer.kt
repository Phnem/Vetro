package com.example.myapplication.manga.translate.pipeline

import kotlinx.serialization.Serializable

/** Реплика страницы с прочитанным текстом и (когда придёт) переводом. */
@Serializable
data class AnalyzedRegion(
    val region: TextRegion,
    val source: String,
    val translation: String? = null,
)

/**
 * Итог разбора страницы без перевода: размеры и реплики. Хранится в кэше отдельно от картинки -
 * смена языка, шрифта или способа стирания пересобирает страницу без детектора и OCR.
 */
@Serializable
data class AnalyzedPage(
    val width: Int,
    val height: Int,
    val regions: List<AnalyzedRegion>,
)

/** Детектор + склейка в реплики + OCR каждого блока. Только вычисления: ни сети, ни диска. */
class PageAnalyzer(
    private val detector: TextRegionDetector,
    private val ocr: MangaOcr,
) {
    fun analyze(page: RgbImage, rightToLeft: Boolean): AnalyzedPage {
        val regions = RegionGrouping.group(detector.detect(page), rightToLeft)
        val analyzed = ArrayList<AnalyzedRegion>(regions.size)
        for (region in regions) {
            val text = region.textBoxes.joinToString("") { box -> ocr.recognize(page.crop(padded(box, page))) }
            if (isTranslatable(text)) analyzed += AnalyzedRegion(region, text)
        }
        return AnalyzedPage(page.width, page.height, analyzed)
    }

    /**
     * Только расположение реплик, без чтения текста: для языков, которые читает не manga-ocr
     * (английский читает модель с картинками). Текст [AnalyzedRegion.source] остаётся пустым.
     */
    fun detectRegions(page: RgbImage, rightToLeft: Boolean): AnalyzedPage {
        val regions = RegionGrouping.group(detector.detect(page), rightToLeft)
        return AnalyzedPage(page.width, page.height, regions.map { AnalyzedRegion(it, "") })
    }

    /** Лёгкий запас вокруг рамки: OCR хуже читает знаки, срезанные по самому краю. */
    private fun padded(box: Box, page: RgbImage): Box {
        val pad = (minOf(box.width, box.height) * CROP_PAD_RATIO).coerceIn(2f, 8f)
        return box.expand(pad, pad).clampTo(page.width, page.height)
    }

    companion object {
        private const val CROP_PAD_RATIO = 0.04f

        /**
         * Не всё прочитанное - реплика: номера страниц и одиночные знаки препинания, которые OCR
         * "видит" в пятнах растра, переводить нечего. Многоточие и "?!" - наоборот, реплики
         * (реакция героя), их оставляем.
         */
        fun isTranslatable(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            if (t.all { it.isDigit() }) return false
            if (t.length == 1 && t in STRAY_MARKS) return false
            return true
        }

        private const val STRAY_MARKS = "、。,.・ー-_'\"`~|/\\"
    }
}
