package com.example.myapplication.manga.translate.pipeline

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/** Открывает четыре файла моделей и собирает из них [PageAnalyzer]. Закрывать - через [close]. */
class OpenedAnalyzer(
    val analyzer: PageAnalyzer,
    private val detector: TextRegionDetector,
    private val ocr: MangaOcr,
) : AutoCloseable {
    override fun close() {
        detector.close()
        ocr.close()
    }
}

object PageAnalyzerFactory {

    fun open(
        detectorFile: File,
        encoderFile: File,
        decoderFile: File,
        vocabFile: File,
        threads: Int = defaultThreads(),
    ): OpenedAnalyzer {
        val environment = OrtEnvironment.getEnvironment()
        val options = {
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
        }
        val detector = TextRegionDetector(environment, environment.createSession(detectorFile.absolutePath, options()))
        val ocr = MangaOcr(
            environment,
            environment.createSession(encoderFile.absolutePath, options()),
            environment.createSession(decoderFile.absolutePath, options()),
            MangaOcr.loadVocabulary(vocabFile.readLines()),
        )
        return OpenedAnalyzer(PageAnalyzer(detector, ocr), detector, ocr)
    }

    /** Половина ядер, но не больше 4: остальным занят интерфейс и сеть, а прирост дальше мал. */
    private fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
