package com.example.myapplication.audiobooks.text

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.chapters.BookAudioReader
import com.example.myapplication.audiobooks.chapters.RecoveredChapterStore
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.media.subtitles.whisper.SpeechEngine
import com.example.myapplication.media.subtitles.whisper.SubtitleCue
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** «Создать на устройстве»: что с распознаванием озвучки. */
sealed interface DeviceSubtitleState {
    data object Idle : DeviceSubtitleState
    data object NeedsModel : DeviceSubtitleState
    data class Working(val percent: Int) : DeviceSubtitleState
    data class Paused(val percent: Int) : DeviceSubtitleState
    data object Ready : DeviceSubtitleState
    data object Failed : DeviceSubtitleState
}

@Serializable
data class DeviceCue(val startMs: Long, val endMs: Long, val text: String)

@Serializable
private data class DeviceSubtitleFile(
    val audioFingerprint: String,
    val model: String,
    val blocks: List<Long> = emptyList(),
    val cues: List<DeviceCue> = emptyList(),
)

/**
 * Субтитры аудиокниги, распознанные на устройстве (Whisper), — когда текста книги нет и
 * пользователь сам выбрал этот пункт. Показывается распознанный текст. Хранилище своё
 * (`files/audiobooks/asr-subtitles`), с синхронизацией текста книги не пересекается.
 */
@UnstableApi
class DeviceSubtitleManager(
    private val context: Context,
    private val resolver: ManifestResolver,
    private val reader: BookAudioReader,
    private val models: WhisperModelStore,
    private val speech: SpeechEngine,
    private val scope: CoroutineScope,
    private val root: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _states = MutableStateFlow<Map<VariantId, DeviceSubtitleState>>(emptyMap())
    val states: StateFlow<Map<VariantId, DeviceSubtitleState>> = _states.asStateFlow()
    private val _cues = MutableStateFlow<Map<VariantId, List<DeviceCue>>>(emptyMap())
    val cues: StateFlow<Map<VariantId, List<DeviceCue>>> = _cues.asStateFlow()
    private val jobs = HashMap<VariantId, Job>()

    @Synchronized
    fun enable(variant: VariantId, language: String?, position: () -> Long?) {
        if (jobs[variant]?.isActive == true) return
        jobs[variant] = scope.launch(Dispatchers.IO) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                run(variant, language, position)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "device subtitles failed: ${e.message}")
                set(variant, DeviceSubtitleState.Failed)
            }
        }
    }

    @Synchronized
    fun disable(variant: VariantId) {
        jobs.remove(variant)?.cancel()
    }

    private suspend fun run(variant: VariantId, language: String?, position: () -> Long?) {
        val (modelKind, model) = listOf(WhisperModel.SMALL, WhisperModel.BASE, WhisperModel.TINY)
            .firstNotNullOfOrNull { m -> models.readyFile(m)?.let { m to it } }
            ?.takeIf { speech.isAvailable }
            ?: run { set(variant, DeviceSubtitleState.NeedsModel); return }
        val manifest = resolver.rawManifest(variant)
        val timeline = BookTimeline(manifest.tracks, manifest.chapters)
        val total = timeline.totalMs ?: run { set(variant, DeviceSubtitleState.Failed); return }
        val print = RecoveredChapterStore.fingerprint(manifest)
        val file = File(root, hash(variant.value) + ".json")
        var data = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(DeviceSubtitleFile.serializer(), file.readText()) }.getOrNull()
        }?.takeIf { it.audioFingerprint == print } ?: DeviceSubtitleFile(print, modelKind.name)
        _cues.update { it + (variant to data.cues) }
        val blocks = ((total + BLOCK_MS - 1) / BLOCK_MS).toInt()
        while (true) {
            val done = data.blocks.map { (it / BLOCK_MS).toInt() }.toSet()
            val percent = done.size * 100 / blocks.coerceAtLeast(1)
            if (done.size >= blocks) { set(variant, DeviceSubtitleState.Ready); return }
            val current = ((position() ?: 0L) / BLOCK_MS).toInt().coerceIn(0, blocks - 1)
            // Только у места прослушивания и на полчаса вперёд: генерация дороже синхронизации.
            val next = (current..minOf(blocks - 1, current + AHEAD_BLOCKS)).firstOrNull { it !in done }
            if (next == null || tooHot()) {
                set(variant, DeviceSubtitleState.Paused(percent))
                delay(IDLE_POLL_MS)
                continue
            }
            set(variant, DeviceSubtitleState.Working(percent))
            val start = next * BLOCK_MS
            val end = minOf(total, start + BLOCK_MS)
            val cues = ArrayList<DeviceCue>()
            var t = start
            while (t < end) {
                val (track, offset) = timeline.toTrack(t) ?: break
                val left = (timeline.trackDurationMs(track) ?: (end - t)) - offset
                val length = minOf(end - t, left).coerceAtLeast(1_000)
                val pcm = reader.speech(Uri.parse(TrackUriCodec.encode(variant, track)), offset, length)
                if (pcm.isNotEmpty()) {
                    val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
                    speech.transcribe(model, pcm, language, threads) {}.cues
                        .filterNot(::isNoise)
                        .forEach { c -> cues += DeviceCue(t + c.startMs, t + c.endMs, c.text.trim()) }
                }
                t += length
            }
            data = data.copy(blocks = (data.blocks + start).distinct().sorted(), cues = (data.cues + cues).sortedBy { it.startMs })
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(DeviceSubtitleFile.serializer(), data))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
            _cues.update { it + (variant to data.cues) }
        }
    }

    private fun isNoise(c: SubtitleCue): Boolean {
        val t = c.text.trim()
        return t.isEmpty() || (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))
    }

    private fun tooHot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        (context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE

    private fun set(variant: VariantId, state: DeviceSubtitleState) = _states.update { it + (variant to state) }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    companion object {
        private const val TAG = "DeviceSubtitles"
        private const val BLOCK_MS = 3 * 60_000L
        private const val AHEAD_BLOCKS = 10
        private const val IDLE_POLL_MS = 10_000L

        /** Реплика на позиции (шкала медиа). */
        fun at(cues: List<DeviceCue>, globalMs: Long): DeviceCue? =
            cues.lastOrNull { it.startMs <= globalMs }?.takeIf { globalMs < it.endMs + 800 }
    }
}
