package com.example.myapplication.media.subtitles.whisper

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * whisper.cpp через JNI (`libvetro_whisper.so`, app/src/main/cpp). Контекст модели загружается один
 * раз и живёт, пока не сменится файл модели: загрузка small занимает секунды. Распознавание — на CPU
 * (NEON) в [threads] потоков; звук не покидает устройство.
 */
class WhisperCppEngine : SpeechEngine {
    override val isAvailable: Boolean = loaded

    private val lock = Mutex()
    private var context = 0L
    private var contextModel: String? = null

    override suspend fun transcribe(
        model: File,
        pcm: FloatArray,
        language: String?,
        threads: Int,
        onProgress: (Int) -> Unit,
    ): Transcription = withContext(Dispatchers.Default) {
        check(isAvailable) { "whisper.cpp is not in this build" }
        lock.withLock {
            if (contextModel != model.absolutePath) {
                if (context != 0L) nativeFree(context)
                context = nativeInit(model.absolutePath)
                check(context != 0L) { "Failed to load ${model.name}" }
                contextModel = model.absolutePath
            }
            val raw = nativeTranscribe(context, pcm, language ?: "auto", threads, ProgressSink(onProgress))
            parse(raw)
        }
    }

    /** Нативная сторона вызывает [onProgress] из потока распознавания. */
    class ProgressSink(private val onProgress: (Int) -> Unit) {
        @Suppress("unused")
        fun onProgress(percent: Int) = onProgress.invoke(percent)
    }

    private companion object {
        val loaded: Boolean = runCatching { System.loadLibrary("vetro_whisper") }.isSuccess

        @JvmStatic external fun nativeInit(modelPath: String): Long
        @JvmStatic external fun nativeFree(context: Long)

        /**
         * Возвращает строки «язык» и затем «t0_ms\tt1_ms\tтекст» — без JSON на нативной стороне и без
         * объектов на каждую реплику через JNI.
         */
        @JvmStatic external fun nativeTranscribe(context: Long, pcm: FloatArray, language: String, threads: Int, progress: ProgressSink): Array<String>

        fun parse(raw: Array<String>): Transcription {
            val language = raw.firstOrNull()?.takeIf { it.isNotBlank() }
            val cues = raw.drop(1).mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@mapNotNull null
                SubtitleCue(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1].toLongOrNull() ?: return@mapNotNull null, parts[2].trim())
            }
            return Transcription(language, cues)
        }
    }
}
