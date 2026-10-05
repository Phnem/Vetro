package com.example.myapplication.manga.translate.pipeline

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Прогон ВСЕГО конвейера настоящими моделями на настоящей странице. Без скачанных моделей тест
 * пропускается: в CI их нет и качать по 128 МБ ради юнит-тестов незачем. Локально:
 *
 *   VETRO_MT_DIR=<папка с detector.onnx, ocr-encoder.onnx, ocr-decoder.onnx, ocr-vocab.txt>
 *   VETRO_MT_PAGE=<сырой снимок страницы: int32 W, int32 H (LE) + RGB>
 *
 * Что именно должно прочитаться, проверено вручную на прототипе: эталон - вывод Python-версии
 * (onnxruntime + PIL) на той же странице.
 */
class RealModelsPipelineTest {

    /** Сырой снимок страницы: int32 ширина, int32 высота (LE), затем RGB по байту. */
    private fun loadPage(file: File): RgbImage {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val w = buffer.int
        val h = buffer.int
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            pixels[i] = (r shl 16) or (g shl 8) or b
        }
        return RgbImage(w, h, pixels)
    }

    @Test
    fun the_page_is_read_like_the_python_prototype() {
        val dir = System.getenv("VETRO_MT_DIR")?.let(::File)
        val pageFile = System.getenv("VETRO_MT_PAGE")?.let(::File)
        assumeTrue("models/page not provided", dir != null && pageFile != null && pageFile.isFile)
        val opened = PageAnalyzerFactory.open(
            File(dir, "detector.onnx"), File(dir, "ocr-encoder.onnx"),
            File(dir, "ocr-decoder.onnx"), File(dir, "ocr-vocab.txt"),
        )
        opened.use {
            val page = loadPage(pageFile!!)
            val started = System.nanoTime()
            val analyzed = opened.analyzer.analyze(page, rightToLeft = true)
            val ms = (System.nanoTime() - started) / 1_000_000
            println("analysis ${ms}ms, ${analyzed.regions.size} regions")
            analyzed.regions.forEach { println("  ${it.region.kind} ${it.region.textBounds} -> ${it.source}") }

            val texts = analyzed.regions.map { it.source }
            assertTrue("regions: ${texts.size}", texts.size in 9..14)
            fun anyContains(part: String) = texts.any { part in it }
            assertTrue(anyContains("必ず君に勝利して見せるぞ"))
            assertTrue(anyContains("姉さん"))
            assertTrue(anyContains("熱意を持っている事だ"))
            assertTrue(anyContains("煌々と燃え上がる"))
            assertTrue(anyContains("その手に握られた剣"))
            assertTrue(anyContains("宙を舞う剣の一本一本には"))
            // Реплики идут в порядке чтения: сверху вниз; верхний ряд - раньше нижнего.
            val firstTop = analyzed.regions.first().region.textBounds.top
            val lastTop = analyzed.regions.last().region.textBounds.top
            assertTrue("reading order: first=$firstTop last=$lastTop", firstTop < lastTop)
        }
    }
}
