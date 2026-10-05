package com.example.myapplication.manga.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.manga.translate.pipeline.PageAnalyzerFactory
import com.example.myapplication.manga.translate.pipeline.RgbImage
import com.example.myapplication.manga.translate.render.PageRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Весь конвейер на Android-рантайме: ONNX Runtime с телефонными библиотеками, декодирование,
 * детектор + OCR и отрисовка перевода настоящим Canvas/StaticLayout. Вход (кладётся adb через
 * run-as): модели в filesDir/manga_translate_models и страница test_page.jpg в filesDir. Перевод
 * подставляется готовый (ключа ИИ у теста нет) - проверяется всё, кроме сети.
 * Результат - filesDir/rendered.png и timings.txt - забирается обратно через adb.
 */
@RunWith(AndroidJUnit4::class)
class TranslatedPageDeviceTest {

    private val russian = listOf(
        "Ради этого я и играл до сих пор, и я не отступлю!",
        "Игроку важнее всего пылать яростью, пока горит огонь в сердце...",
        "Никто не посмеет коснуться того, что внутри меня!",
        "Это мой путь.",
        "Пылающий, яркий,",
        "душа горит жаром!!",
        "Оружие в той руке...",
        "В каждом из парящих клинков",
        "Год упорного труда, потраченного времени и огненной страсти к Шангри-Ла Фронтир",
        "Сестра...",
        "...да что угодно...!!",
        "Я обязательно покажу тебе победу...!!",
    )

    @Test
    fun the_page_is_analysed_translated_and_drawn_on_android() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = File(context.filesDir, "manga_translate_models")
        val pageFile = File(context.filesDir, "test_page.jpg")
        assumeTrue("models/page not pushed", pageFile.isFile && TranslationModels.detectorFile(models).isFile)

        val bitmap = BitmapFactory.decodeFile(pageFile.absolutePath)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) pixels[i] = pixels[i] and 0xFFFFFF
        val rgb = RgbImage(w, h, pixels)

        val log = StringBuilder()
        val opened = PageAnalyzerFactory.open(
            TranslationModels.detectorFile(models), TranslationModels.encoderFile(models),
            TranslationModels.decoderFile(models), TranslationModels.vocabFile(models),
        )
        opened.use {
            val t0 = System.nanoTime()
            val analyzed = opened.analyzer.analyze(rgb, rightToLeft = true)
            log.appendLine("analysis ms=${(System.nanoTime() - t0) / 1_000_000} regions=${analyzed.regions.size}")
            analyzed.regions.forEachIndexed { i, r -> log.appendLine("  $i ${r.region.kind} ${r.source}") }

            val translated = analyzed.copy(
                regions = analyzed.regions.mapIndexed { i, r -> r.copy(translation = russian[i % russian.size]) },
            )
            val t1 = System.nanoTime()
            val out = PageRenderer.render(bitmap, rgb, translated)
            log.appendLine("render ms=${(System.nanoTime() - t1) / 1_000_000}")
            FileOutputStream(File(context.filesDir, "rendered.png")).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(context.filesDir, "timings.txt").writeText(log.toString())
        println(log)
    }
}
