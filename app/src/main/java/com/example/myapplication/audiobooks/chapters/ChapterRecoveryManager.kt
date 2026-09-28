package com.example.myapplication.audiobooks.chapters

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.media.subtitles.whisper.SpeechEngine
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import java.io.File
import java.security.MessageDigest
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

/** Что сейчас с поиском глав у озвучки. Готовое предложение живёт в [RecoveredChapterStore]. */
sealed interface RecoveryState {
    data object Idle : RecoveryState
    /** [stage]: разметка → паузы → речь; [progress] 0..1 внутри этапа. */
    data class Running(val stage: Stage, val progress: Float) : RecoveryState
    data class Paused(val reason: PauseReason) : RecoveryState
    data object NothingFound : RecoveryState
    data class Failed(val reason: FailReason) : RecoveryState

    enum class Stage { METADATA, SILENCE, SPEECH }
    enum class PauseReason { HEAT, BATTERY }
    enum class FailReason { NEEDS_WIFI, UNREADABLE }
}

/**
 * Фоновый поиск глав: метаданные (CUE) → огибающая громкости всей книги → заголовки после длинных
 * пауз (Whisper, если модель скачана). Работа возобновляемая: огибающая пишется на диск по минутам
 * и после убитого процесса продолжается с того же места. На перегреве и низком заряде — пауза.
 */
@UnstableApi
class ChapterRecoveryManager(
    private val context: Context,
    private val resolver: ManifestResolver,
    private val store: RecoveredChapterStore,
    private val reader: BookAudioReader,
    private val local: LocalFolderSource,
    private val models: WhisperModelStore,
    private val speech: SpeechEngine,
    private val scope: CoroutineScope,
    private val workDir: File,
) {
    private val _states = MutableStateFlow<Map<VariantId, RecoveryState>>(emptyMap())
    val states: StateFlow<Map<VariantId, RecoveryState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<VariantId, Job>()

    /** [russian] — язык распознавания и слово «Глава»/«Chapter» у глав без заголовка. */
    @Synchronized
    fun start(variant: VariantId, russian: Boolean) {
        if (jobs[variant]?.isActive == true) return
        set(variant, RecoveryState.Running(RecoveryState.Stage.METADATA, 0f))
        jobs[variant] = scope.launch(Dispatchers.IO) {
            val priority = Process.getThreadPriority(Process.myTid())
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                set(variant, run(variant, russian))
            } catch (e: CancellationException) {
                set(variant, RecoveryState.Idle)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "chapter recovery failed: ${e.message}")
                set(variant, RecoveryState.Failed(RecoveryState.FailReason.UNREADABLE))
            } finally {
                runCatching { Process.setThreadPriority(priority) }
            }
        }
    }

    @Synchronized
    fun cancel(variant: VariantId) {
        jobs.remove(variant)?.cancel()
        set(variant, RecoveryState.Idle)
    }

    suspend fun apply(variant: VariantId) {
        store.setApplied(variant, true)
        resolver.invalidate(variant)
        scratch(variant).deleteRecursively()
    }

    /** «Не надо» и «Вернуть исходные» — одно: снова разметка источника, и сам поиск больше не запускается. */
    suspend fun decline(variant: VariantId) {
        store.setApplied(variant, false)
        resolver.invalidate(variant)
        scratch(variant).deleteRecursively()
        set(variant, RecoveryState.Idle)
    }

    /** «Искать снова»: прошлый результат забыт, поиск — с начала. */
    suspend fun restart(variant: VariantId, russian: Boolean) {
        cancel(variant)
        store.remove(variant)
        resolver.invalidate(variant)
        scratch(variant).deleteRecursively()
        start(variant, russian)
    }

    private suspend fun run(variant: VariantId, russian: Boolean): RecoveryState {
        val manifest = resolver.rawManifest(variant)
        val fingerprint = RecoveredChapterStore.fingerprint(manifest)
        val word = if (russian) "Глава" else "Chapter"

        // 1. Разметка рядом с файлом: CUE для книги одним файлом — точнее любых догадок.
        if (manifest.tracks.size == 1) {
            val cue = local.takeIf { it.supports(variant) }?.cueSheet(variant)?.let(CueSheet::parse).orEmpty()
            if (cue.size >= 2) return propose(variant, fingerprint, cue)
        }

        // Книга с сайта читается целиком — по мобильной сети это сотни мегабайт.
        val remote = manifest.tracks.any { Uri.parse(it.url).scheme !in LOCAL_SCHEMES }
        if (remote && isMetered()) return RecoveryState.Failed(RecoveryState.FailReason.NEEDS_WIFI)

        // 2. Огибающая громкости каждого трека (с продолжением), затем паузы по всей книге.
        val dir = scratch(variant).apply { mkdirs() }
        val envelopes = ArrayList<ByteArray>(manifest.tracks.size)
        for (track in manifest.tracks) {
            val file = File(dir, "${track.index}.env")
            val done = File(dir, "${track.index}.done")
            if (!done.exists()) {
                val already = if (file.exists()) file.length().toInt() else 0
                java.io.FileOutputStream(file, already > 0).use { out ->
                    reader.envelope(
                        uri = Uri.parse(TrackUriCodec.encode(variant, track.index)),
                        fromFrame = already,
                        sink = { out.write(it); out.flush() },
                        onProgress = { p ->
                            set(variant, RecoveryState.Running(RecoveryState.Stage.SILENCE, (track.index + p) / manifest.tracks.size))
                        },
                        checkpoint = { waitUntilComfortable(variant, RecoveryState.Stage.SILENCE) },
                    )
                }
                done.createNewFile()
            }
            envelopes += file.readBytes()
        }
        val trackStarts = envelopes.runningFold(0L) { acc, e -> acc + e.size * SilenceDetector.FRAME_MS }
        val totalMs = trackStarts.last()
        val whole = java.io.ByteArrayOutputStream(envelopes.sumOf { it.size }).apply { envelopes.forEach { e -> write(e) } }
        val gaps = SilenceDetector.gaps(whole.toByteArray())

        // 3. Что звучит после самых длинных пауз: «Глава третья» подтверждает границу и даёт название.
        val headings = HashMap<SilenceGap, Heading>()
        var opening: Heading? = null
        val model = models.anyReady(WhisperModel.BASE)?.second
        if (model != null && speech.isAvailable) {
            val language = if (russian) "ru" else "en"
            val candidates = ChapterProposer.candidates(gaps, totalMs).sortedBy { it.endMs }
            val points = listOf<SilenceGap?>(null) + candidates
            for ((i, gap) in points.withIndex()) {
                set(variant, RecoveryState.Running(RecoveryState.Stage.SPEECH, i.toFloat() / points.size))
                waitUntilComfortable(variant, RecoveryState.Stage.SPEECH)
                val at = gap?.let(ChapterProposer::boundaryOf) ?: 0L
                val trackIndex = (trackStarts.indexOfLast { it <= at }).coerceIn(0, manifest.tracks.lastIndex)
                val pcm = runCatching {
                    reader.speech(Uri.parse(TrackUriCodec.encode(variant, trackIndex)), at - trackStarts[trackIndex], SPEECH_WINDOW_MS)
                }.getOrNull() ?: continue
                if (pcm.isEmpty()) continue
                val text = runCatching {
                    speech.transcribe(model, pcm, language, WHISPER_THREADS) {}.cues.joinToString(" ") { it.text }
                }.getOrNull() ?: continue
                val heading = HeadingParser.parse(text) ?: continue
                if (gap == null) opening = heading else headings[gap] = heading
            }
        }

        val fixed = if (manifest.tracks.size > 1) trackStarts.dropLast(1) else emptyList()
        val chapters = ChapterProposer.propose(totalMs, gaps, headings, opening, fixed, word)
        scratch(variant).deleteRecursively()
        // «Не нашлось» тоже запоминается (пустым списком): книга не разбирается заново при каждом запуске.
        val useful = chapters.size >= 2 && chapters.size > manifest.chapters.size
        return propose(variant, fingerprint, if (useful) chapters else emptyList())
    }

    private suspend fun propose(variant: VariantId, fingerprint: String, chapters: List<RecoveredChapter>): RecoveryState {
        Log.i(TAG, "found ${chapters.size} chapters, ${chapters.count { it.confirmed }} confirmed: ${chapters.joinToString { "${it.startMs / 1000}s ${it.title}" }}")
        store.save(variant, RecoveredChapters(fingerprint, chapters, applied = false, createdAt = System.currentTimeMillis()))
        return RecoveryState.Idle
    }

    /** Перегрев и низкий заряд без зарядки — ждём, показывая причину. */
    private suspend fun waitUntilComfortable(variant: VariantId, stage: RecoveryState.Stage) {
        var paused = false
        while (true) {
            val reason = when {
                tooHot() -> RecoveryState.PauseReason.HEAT
                lowBattery() -> RecoveryState.PauseReason.BATTERY
                else -> null
            } ?: break
            paused = true
            set(variant, RecoveryState.Paused(reason))
            delay(PAUSE_POLL_MS)
        }
        if (paused) set(variant, RecoveryState.Running(stage, 0f))
    }

    private fun tooHot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        (context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE

    private fun lowBattery(): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return false
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 0 until LOW_BATTERY_PERCENT && !battery.isCharging
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private fun scratch(variant: VariantId): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(variant.value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        return File(workDir, hash)
    }

    private fun set(variant: VariantId, state: RecoveryState) = _states.update { it + (variant to state) }

    companion object {
        private const val TAG = "ChapterRecovery"
        private val LOCAL_SCHEMES = setOf("content", "file")
        /** Сколько секунд после паузы слушать: «Глава двадцать третья. Возвращение» укладывается. */
        private const val SPEECH_WINDOW_MS = 7_000L
        /** Фоновая работа: половина ядер, не больше четырёх — плеер и интерфейс не должны заикаться. */
        private val WHISPER_THREADS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        private const val LOW_BATTERY_PERCENT = 15
        private const val PAUSE_POLL_MS = 30_000L

        /**
         * Разметка «бедная» — есть что восстанавливать: одна глава на всю книгу или в среднем глава
         * длиннее часа (файлы по несколько часов).
         */
        fun needsRecovery(manifest: MediaManifest): Boolean {
            val total = manifest.tracks.sumOf { it.durationMs ?: return manifest.chapters.size <= 1 }
            val count = manifest.chapters.size.coerceAtLeast(1)
            return total >= 2 * ChapterProposer.MIN_CHAPTER_MS && (count == 1 || total / count > POOR_CHAPTER_MS)
        }

        private const val POOR_CHAPTER_MS = 60 * 60_000L
    }
}
