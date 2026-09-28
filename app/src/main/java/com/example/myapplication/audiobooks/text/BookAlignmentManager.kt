package com.example.myapplication.audiobooks.text

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.chapters.BookAudioReader
import com.example.myapplication.audiobooks.chapters.RecoveredChapterStore
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.audiobooks.text.source.BookTextSource
import com.example.myapplication.audiobooks.text.source.LocalTextSource
import com.example.myapplication.audiobooks.text.source.TextCandidate
import com.example.myapplication.audiobooks.text.source.TextMatch
import com.example.myapplication.audiobooks.text.source.TextQuery
import com.example.myapplication.media.subtitles.whisper.SpeechEngine
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что с «Текстом книги» у озвучки — строка под пунктом меню. */
sealed interface BookTextState {
    data object Idle : BookTextState
    data object Searching : BookTextState
    data object NotFound : BookTextState
    /** Текст нашёлся, но с записью не совпадает (другой перевод, другая книга). */
    data class Mismatch(val sourceName: String?) : BookTextState
    /** Текст есть, а модели распознавания для синхронизации нет. */
    data class NeedsModel(val sourceName: String) : BookTextState
    data object NoSpace : BookTextState
    data class Syncing(val sourceName: String, val percent: Int) : BookTextState
    data class Paused(val sourceName: String, val percent: Int, val hot: Boolean) : BookTextState
    data class Ready(val sourceName: String) : BookTextState
    data object Failed : BookTextState
}

/** Готовые реплики озвучки и текст, из которого они берутся. */
class CueTrack(val book: BookText, val cues: List<AlignedCue>) {
    /** Реплика на позиции [globalMs] (шкала медиа — скорость воспроизведения ни при чём). */
    fun at(globalMs: Long): AlignedCue? {
        var lo = 0
        var hi = cues.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= globalMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        val cue = cues[found]
        val next = cues.getOrNull(found + 1)
        val end = when {
            cue.openEnd -> next?.startMs ?: (cue.startMs + OPEN_END_MAX_MS)
            // Короткая пауза между предложениями — реплика не мигает.
            next != null && next.startMs - cue.endMs < HOLD_MS -> next.startMs
            else -> cue.endMs + HOLD_MS
        }
        return cue.takeIf { globalMs < end && it.confidence >= BookAudioAligner.SHOW_THRESHOLD }
    }

    private companion object {
        const val HOLD_MS = 1_500L
        const val OPEN_END_MAX_MS = 15_000L
    }
}

/**
 * Синхронизация текста книги с аудио: находит текст, проверяет, что он тот самый, и выравнивает
 * блоками по 3 минуты — сначала вокруг места прослушивания и на полчаса вперёд, потом, если
 * телефон не горячий и заряд есть, остальную книгу. Каждый блок сохраняется: закрытое приложение
 * продолжит с того же места, а посчитанное не считается заново.
 */
@UnstableApi
class BookAlignmentManager(
    private val context: Context,
    private val resolver: ManifestResolver,
    private val repository: AudiobookRepository,
    private val sources: List<BookTextSource>,
    private val store: AlignmentStore,
    private val reader: BookAudioReader,
    private val models: WhisperModelStore,
    private val speech: SpeechEngine,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<VariantId, BookTextState>>(emptyMap())
    val states: StateFlow<Map<VariantId, BookTextState>> = _states.asStateFlow()

    private val _tracks = MutableStateFlow<Map<VariantId, CueTrack>>(emptyMap())
    val tracks: StateFlow<Map<VariantId, CueTrack>> = _tracks.asStateFlow()

    private val jobs = HashMap<VariantId, Job>()

    /** Включить «Текст книги» для озвучки; [position] — где сейчас слушают (шкала книги). */
    @Synchronized
    fun enable(variant: VariantId, narration: NarrationId, position: () -> Long?) {
        if (jobs[variant]?.isActive == true) return
        jobs[variant] = scope.launch(Dispatchers.IO) {
            val priority = Process.getThreadPriority(Process.myTid())
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                run(variant, narration, position)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "alignment failed: ${e.message}", e)
                set(variant, BookTextState.Failed)
            } finally {
                runCatching { Process.setThreadPriority(priority) }
            }
        }
    }

    @Synchronized
    fun disable(variant: VariantId) {
        jobs.remove(variant)?.cancel()
    }

    /** Файл книги выбран вручную: прежний текст и его синхронизация забываются. */
    suspend fun useFile(variant: VariantId, narration: NarrationId, uri: Uri, position: () -> Long?) {
        disable(variant)
        sources.filterIsInstance<LocalTextSource>().firstOrNull()?.choose(variant, uri)
        store.forget(variant)
        _tracks.update { it - variant }
        enable(variant, narration, position)
    }

    /** «Искать снова»: забыть отказ и найденное, начать с поиска. */
    suspend fun retry(variant: VariantId, narration: NarrationId, position: () -> Long?) {
        disable(variant)
        store.forget(variant)
        _tracks.update { it - variant }
        enable(variant, narration, position)
    }

    // ---------- Работа ----------

    private suspend fun run(variant: VariantId, narration: NarrationId, position: () -> Long?) {
        val manifest = resolver.rawManifest(variant)
        val timeline = BookTimeline(manifest.tracks, manifest.chapters)
        val total = timeline.totalMs ?: run { set(variant, BookTextState.Failed); return }
        val audioPrint = RecoveredChapterStore.fingerprint(manifest)

        // 1. Текст: уже найденный — или поиск по источникам с проверкой содержимого.
        var stored = store.text(variant)
        if (stored == null) {
            store.miss(variant)?.takeIf { System.currentTimeMillis() - it.at < MISS_TTL_MS }?.let { miss ->
                set(variant, if (miss.mismatch) BookTextState.Mismatch(null) else BookTextState.NotFound)
                return
            }
            if (context.filesDir.usableSpace < MIN_FREE_BYTES) { set(variant, BookTextState.NoSpace); return }
            set(variant, BookTextState.Searching)
            stored = findAndVerify(variant, narration, manifest, timeline, total, audioPrint, position) ?: return
        }
        val book = stored.toBookText()
        val aligner = BookAudioAligner(book)
        var file = store.alignment(variant)
            ?.takeIf { it.algorithm == ALGORITHM && it.audioFingerprint == audioPrint && it.textFingerprint == book.fingerprint }
            ?: AlignmentFile(ALGORITHM, audioPrint, book.fingerprint)
        if (file.mismatch) { set(variant, BookTextState.Mismatch(stored.sourceName)); return }
        publish(variant, book, file)

        val model = modelFile() ?: run { set(variant, BookTextState.NeedsModel(stored.sourceName)); return }
        val blocks = ((total + BLOCK_MS - 1) / BLOCK_MS).toInt()

        // 2. Блоки: у места прослушивания и вперёд, затем остальное — медленнее.
        while (true) {
            val done = file.blocks.map { (it.startMs / BLOCK_MS).toInt() }.toSet()
            val percent = (done.size * 100 / blocks.coerceAtLeast(1))
            if (done.size >= blocks) { set(variant, BookTextState.Ready(stored.sourceName)); return }
            val current = ((position() ?: 0L) / BLOCK_MS).toInt().coerceIn(0, blocks - 1)
            val near = (current..minOf(blocks - 1, current + AHEAD_BLOCKS)).firstOrNull { it !in done }
            val next = near ?: if (canBackground()) {
                ((current until blocks) + (0 until current)).firstOrNull { it !in done }
            } else null
            if (next == null) {
                set(variant, BookTextState.Paused(stored.sourceName, percent, hot = tooHot(PowerManager.THERMAL_STATUS_MODERATE)))
                delay(IDLE_POLL_MS)
                continue
            }
            if (tooHot(PowerManager.THERMAL_STATUS_SEVERE)) {
                set(variant, BookTextState.Paused(stored.sourceName, percent, hot = true))
                delay(IDLE_POLL_MS)
                continue
            }
            set(variant, BookTextState.Syncing(stored.sourceName, percent))
            val start = next * BLOCK_MS
            val end = minOf(total, start + BLOCK_MS)
            val words = transcribe(variant, timeline, start, end, model, language(stored, book), background = near == null)
            val expected = aligner.expectedToken(start, file.anchors, total)
            val result = aligner.align(words, expected, end)
            file = merge(file, AlignedBlock(start, end, result.matchRatio), result, book)
            store.saveAlignment(variant, file)
            publish(variant, book, file)
            if (near == null) delay(BACKGROUND_PAUSE_MS)
        }
    }

    /**
     * Кандидаты от всех источников, от точных к похожим; первый, чей текст нашёлся в речи двух
     * пробных блоков, принимается и сохраняется вместе с этими блоками.
     */
    private suspend fun findAndVerify(
        variant: VariantId,
        narration: NarrationId,
        manifest: MediaManifest,
        timeline: BookTimeline,
        total: Long,
        audioPrint: String,
        position: () -> Long?,
    ): StoredText? {
        val book = repository.bookOf(narration)
        if (book == null) Log.i(TAG, "no work for narration ${narration.value}")
        val link = repository.variants(narration).firstOrNull { it.variantId == variant }
        val query = TextQuery(
            title = book?.title ?: return notFound(variant),
            titleOriginal = book.titleOriginal,
            authors = book.authors,
            language = book.language.takeIf { it.isNotBlank() }?.take(2)?.lowercase(),
            variant = variant,
            sourceId = link?.sourceId,
            sourceKey = link?.key,
        )
        val candidates = sources.flatMap { s ->
            runCatching { s.find(query) }
                .onFailure { Log.w(TAG, "find ${s.id}: ${it.message}") }
                .getOrDefault(emptyList())
                .also { found -> Log.i(TAG, "find ${s.id}: ${found.size} (${found.take(3).joinToString { "${it.title}/${TextMatch.score(query, it)}" }})") }
                .map { s to it }
        }
            .map { (s, c) -> Triple(s, c, TextMatch.score(query, c)) }
            .filter { it.third >= MIN_METADATA_SCORE }
            .sortedByDescending { it.third }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return notFound(variant)

        var sawText = false
        for ((source, candidate, _) in candidates) {
            val text = runCatching { source.load(candidate) }.onFailure { Log.w(TAG, "load ${source.id}: ${it.message}") }.getOrNull() ?: continue
            if (query.language != null && text.language != null && text.language.take(2) != query.language) continue
            sawText = true
            val model = modelFile() ?: run { set(variant, BookTextState.NeedsModel(source.displayName)); return null }
            set(variant, BookTextState.Syncing(source.displayName, 0))
            val aligner = BookAudioAligner(text)
            // Проба: место прослушивания и на 10 % книги дальше — текст должен найтись в речи.
            val here = (((position() ?: 0L) / BLOCK_MS) * BLOCK_MS).coerceIn(0, maxOf(0, total - 1))
            val probes = listOf(here, ((here + total / 10) / BLOCK_MS) * BLOCK_MS).distinct().filter { it < total }
            var file = AlignmentFile(ALGORITHM, audioPrint, text.fingerprint)
            for (start in probes) {
                val end = minOf(total, start + BLOCK_MS)
                val words = transcribe(variant, timeline, start, end, model, text.language ?: query.language, background = false)
                val result = aligner.align(words, null, end)
                Log.i(TAG, "probe ${source.id}:${candidate.id} at ${start / 1000}s: ratio=${"%.2f".format(result.matchRatio)} words=${words.size}")
                file = merge(file, AlignedBlock(start, end, result.matchRatio), result, text)
            }
            if (file.blocks.none { it.ratio >= BookAudioAligner.MIN_RATIO }) continue
            val stored = StoredText(
                sourceId = source.id, sourceName = source.displayName, candidateId = candidate.id, title = candidate.title,
                language = text.language ?: query.language, display = text.display,
                chapters = text.chapters.map { StoredChapter(it.title, it.start) }, savedAt = System.currentTimeMillis(),
            )
            store.saveText(variant, stored)
            store.saveAlignment(variant, file)
            return stored
        }
        store.saveMiss(variant, TextMiss(System.currentTimeMillis(), mismatch = sawText))
        set(variant, if (sawText) BookTextState.Mismatch(null) else BookTextState.NotFound)
        return null
    }

    private suspend fun notFound(variant: VariantId): StoredText? {
        store.saveMiss(variant, TextMiss(System.currentTimeMillis()))
        set(variant, BookTextState.NotFound)
        return null
    }

    /** Речь блока пословно, на шкале книги. Блок на стыке файлов читается из обоих. */
    private suspend fun transcribe(
        variant: VariantId,
        timeline: BookTimeline,
        start: Long,
        end: Long,
        model: java.io.File,
        language: String?,
        background: Boolean,
    ): List<AsrWord> {
        val words = ArrayList<AsrWord>()
        var t = start
        while (t < end) {
            val (track, offset) = timeline.toTrack(t) ?: break
            val trackLeft = (timeline.trackDurationMs(track) ?: (end - t)) - offset
            val length = minOf(end - t, trackLeft).coerceAtLeast(1_000)
            val pcm = withContext(Dispatchers.IO) {
                reader.speech(Uri.parse(TrackUriCodec.encode(variant, track)), offset, length)
            }
            if (pcm.isNotEmpty()) {
                val threads = if (background) 2 else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
                val result = speech.transcribeWords(model, pcm, language, threads)
                for (cue in result.cues) {
                    for (token in MatchText.tokens(cue.text, asr = true)) {
                        words += AsrWord(token.key, t + cue.startMs, t + cue.endMs)
                    }
                }
            }
            t += length
        }
        return words
    }

    private fun merge(file: AlignmentFile, block: AlignedBlock, result: BlockAlignment, book: BookText): AlignmentFile {
        // Одно предложение могло попасть в два соседних блока — остаётся более уверенная реплика.
        val merged = (file.cues + result.cues).groupBy { it.sentence }.map { (_, list) -> list.maxBy { it.confidence } }
        val cues = BookAudioAligner.fillGaps(merged, book)
        return file.copy(
            blocks = (file.blocks.filter { it.startMs != block.startMs } + block).sortedBy { it.startMs },
            anchors = (file.anchors + result.anchors).distinctBy { it.token }.sortedBy { it.timeMs },
            cues = cues,
        )
    }

    private fun publish(variant: VariantId, book: BookText, file: AlignmentFile) {
        _tracks.update { it + (variant to CueTrack(book, file.cues)) }
    }

    private fun language(stored: StoredText, book: BookText) = stored.language ?: book.language

    /** Для синхронизации хватает лёгкой модели: ошибки распознавания выравнивание переживает. */
    private fun modelFile(): java.io.File? =
        (models.readyFile(WhisperModel.BASE) ?: models.readyFile(WhisperModel.TINY) ?: models.readyFile(WhisperModel.SMALL))
            ?.takeIf { speech.isAvailable }

    private fun canBackground(): Boolean {
        if (tooHot(PowerManager.THERMAL_STATUS_MODERATE)) return false
        val battery = context.getSystemService(BatteryManager::class.java) ?: return true
        return battery.isCharging || battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) > BACKGROUND_BATTERY
    }

    private fun tooHot(level: Int): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        (context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= level

    private fun set(variant: VariantId, state: BookTextState) = _states.update { it + (variant to state) }

    companion object {
        private const val TAG = "BookAlignment"
        /** Версия алгоритма: смена — старые файлы выравнивания не используются. */
        const val ALGORITHM = 1
        const val BLOCK_MS = 3 * 60_000L
        /** Буфер вперёд от места прослушивания: 30 минут. */
        private const val AHEAD_BLOCKS = 10
        private const val MIN_METADATA_SCORE = 0.6f
        private const val MAX_CANDIDATES = 4
        private const val MISS_TTL_MS = 7L * 24 * 3600_000
        private const val MIN_FREE_BYTES = 100L * 1024 * 1024
        private const val IDLE_POLL_MS = 10_000L
        private const val BACKGROUND_PAUSE_MS = 5_000L
        private const val BACKGROUND_BATTERY = 30
    }
}
