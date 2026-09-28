package com.example.myapplication.media.subtitles.whisper

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import com.example.myapplication.network.AppJson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

data class Transcription(val language: String?, val cues: List<SubtitleCue>)

/** Язык распознавания: вручную или определить по речи. */
enum class WhisperLanguage(val code: String?) { AUTO(null), RU("ru"), EN("en") }

/**
 * Распознавание речи на устройстве (whisper.cpp). [isAvailable] — нативная библиотека есть в сборке и
 * загрузилась; без неё Whisper в меню честно недоступен.
 */
interface SpeechEngine {
    val isAvailable: Boolean

    /** [pcm] — 16 кГц моно; метки времени — от начала [pcm]. [onProgress] — 0..100. */
    suspend fun transcribe(model: File, pcm: FloatArray, language: String?, threads: Int, onProgress: (Int) -> Unit): Transcription

    /**
     * Пословно: каждая реплика — одно слово со своими метками. Нужна выравниванию с текстом книги;
     * движок, который так не умеет, отдаёт фразы.
     */
    suspend fun transcribeWords(model: File, pcm: FloatArray, language: String?, threads: Int): Transcription =
        transcribe(model, pcm, language, threads) {}
}

/** Готовые субтитры на диске: повторное открытие серии не распознаёт заново. */
@Serializable
data class CachedTranscript(
    val model: String,
    val language: String?,
    val detectedLanguage: String? = null,
    /** Уже распознанные диапазоны видео, [start, end) в мс. */
    val covered: List<List<Long>> = emptyList(),
    val cues: List<SubtitleCue> = emptyList(),
)

class WhisperSubtitleCache(private val dir: File) {
    private fun file(key: String) = File(dir, sha1(key) + ".json")

    fun load(key: String): CachedTranscript? = runCatching {
        file(key).takeIf(File::isFile)?.readText()?.let { AppJson.decodeFromString(CachedTranscript.serializer(), it) }
    }.getOrNull()

    fun save(key: String, transcript: CachedTranscript) {
        dir.mkdirs()
        val tmp = File(dir, sha1(key) + ".tmp")
        tmp.writeText(AppJson.encodeToString(CachedTranscript.serializer(), transcript))
        // renameTo не заменяет существующий файл на части файловых систем — кэш застрял бы на первом куске.
        java.nio.file.Files.move(
            tmp.toPath(), file(key).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    fun clear() {
        dir.listFiles()?.forEach(File::delete)
    }

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

enum class WhisperStatus { RUNNING, DONE, STOPPED, FAILED }

data class WhisperProgress(
    val status: WhisperStatus,
    val cues: List<SubtitleCue>,
    val coveredMs: Long,
    val durationMs: Long,
    val detectedLanguage: String?,
    /** Распознавание медленнее самого видео — стоит предложить модель полегче. */
    val slowerThanRealTime: Boolean = false,
)

/**
 * Что распознавать. [key] — серия + озвучка (разные озвучки — разная речь) + язык + модель;
 * [fromMs] — с какого места начинать: сначала то, что пользователь сейчас смотрит.
 */
@OptIn(UnstableApi::class)
data class WhisperRequest(
    val key: String,
    val mediaItem: MediaItem,
    val sourceFactory: MediaSource.Factory,
    val durationMs: Long,
    val fromMs: Long,
    val language: WhisperLanguage,
    val model: WhisperModel,
    val modelFile: File,
)

/**
 * Фоновое распознавание кусками по минуте: звук куска → Whisper → реплики с метками от начала видео →
 * кэш на диске. Начинает с текущего места и идёт к концу, потом дочитывает начало; уже распознанное
 * пропускает. Интерфейс плеера не ждёт — реплики появляются по мере готовности.
 */
@OptIn(UnstableApi::class)
class WhisperSubtitleManager(
    /** Звук диапазона [start, end) видео, 16 кГц моно (в приложении — [AudioExtractor]). */
    private val extract: suspend (WhisperRequest, LongRange) -> FloatArray,
    private val engine: SpeechEngine,
    private val cache: WhisperSubtitleCache,
    private val scope: CoroutineScope,
    private val threads: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val progress = MutableStateFlow<Map<String, WhisperProgress>>(emptyMap())
    private val jobs = mutableMapOf<String, Job>()

    val isEngineAvailable: Boolean get() = engine.isAvailable

    fun progress(key: String): Flow<WhisperProgress?> = progress.map { it[key] }

    /** Готовое из кэша (без запуска распознавания). */
    fun cached(key: String, durationMs: Long): WhisperProgress? = cache.load(key)?.let { c ->
        val covered = c.covered.sumOf { it[1] - it[0] }
        WhisperProgress(
            status = if (covered >= durationMs - CHUNK_MS / 2) WhisperStatus.DONE else WhisperStatus.STOPPED,
            cues = c.cues,
            coveredMs = covered,
            durationMs = durationMs,
            detectedLanguage = c.detectedLanguage,
        )
    }

    @Synchronized
    fun start(request: WhisperRequest) {
        if (jobs[request.key]?.isActive == true) return
        jobs[request.key] = scope.launch(Dispatchers.Default) { run(request) }
    }

    @Synchronized
    fun stop(key: String) {
        jobs.remove(key)?.cancel()
        progress.update { all -> all[key]?.let { all + (key to it.copy(status = WhisperStatus.STOPPED)) } ?: all }
    }

    private suspend fun run(r: WhisperRequest) {
        var transcript = cache.load(r.key) ?: CachedTranscript(r.model.name, r.language.code)
        fun emit(status: WhisperStatus, slow: Boolean = false) = progress.update {
            it + (r.key to WhisperProgress(status, transcript.cues, transcript.covered.sumOf { c -> c[1] - c[0] }, r.durationMs, transcript.detectedLanguage, slow))
        }
        emit(WhisperStatus.RUNNING)
        var slow = false
        try {
            for (chunk in chunkPlan(r.durationMs, r.fromMs, CHUNK_MS, transcript.covered.map { it[0] until it[1] })) {
                if (!scope.isActive) break
                val started = nowMs()
                val pcm = extract(r, chunk)
                val language = r.language.code ?: transcript.detectedLanguage
                val result = withContext(Dispatchers.Default) {
                    engine.transcribe(r.modelFile, pcm, language, threads) {}
                }
                val shifted = result.cues
                    .filter { it.text.isNotBlank() && it.text.trim() !in NOISE }
                    .map { it.copy(startMs = it.startMs + chunk.first, endMs = (it.endMs + chunk.first).coerceAtMost(chunk.last + 1)) }
                transcript = transcript.copy(
                    detectedLanguage = transcript.detectedLanguage ?: result.language,
                    covered = mergeRanges(transcript.covered + listOf(listOf(chunk.first, chunk.last + 1))),
                    cues = (transcript.cues.filterNot { it.startMs in chunk } + shifted).sortedBy { it.startMs },
                )
                cache.save(r.key, transcript)
                slow = slow || (nowMs() - started) > (chunk.last + 1 - chunk.first)
                emit(WhisperStatus.RUNNING, slow)
            }
            emit(WhisperStatus.DONE, slow)
        } catch (e: CancellationException) {
            emit(WhisperStatus.STOPPED, slow)
            throw e
        } catch (e: Exception) {
            emit(WhisperStatus.FAILED, slow)
        }
    }

    companion object {
        const val CHUNK_MS = 60_000L

        /** Служебные метки Whisper вместо речи. */
        private val NOISE = setOf("[BLANK_AUDIO]", "[МУЗЫКА]", "[Музыка]", "[Music]", "[MUSIC]", "(music)")

        /**
         * Куски по [chunkMs]: от куска с [fromMs] до конца, затем с начала до него; уже распознанные
         * пропускаются. Последний кусок короче, если видео кончается раньше.
         */
        fun chunkPlan(durationMs: Long, fromMs: Long, chunkMs: Long, covered: List<LongRange>): List<LongRange> {
            if (durationMs <= 0) return emptyList()
            val all = (0 until durationMs step chunkMs).map { it until minOf(durationMs, it + chunkMs) }
            val first = all.indexOfFirst { fromMs.coerceIn(0, durationMs - 1) in it }.coerceAtLeast(0)
            return (all.drop(first) + all.take(first)).filterNot { chunk -> covered.any { chunk.first >= it.first && chunk.last <= it.last } }
        }

        fun mergeRanges(ranges: List<List<Long>>): List<List<Long>> {
            val sorted = ranges.sortedBy { it[0] }
            val out = mutableListOf<MutableList<Long>>()
            for (r in sorted) {
                val last = out.lastOrNull()
                if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1]) else out += mutableListOf(r[0], r[1])
            }
            return out
        }
    }
}
