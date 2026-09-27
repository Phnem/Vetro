package com.example.myapplication.media.subtitles.whisper

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Модели whisper.cpp (многоязычные, квантованные q5_1) из официального репозитория. Размер и SHA-256 —
 * по данным Hugging Face на 27.09.2026: скачанный файл, не совпавший с хешем, не используется.
 */
enum class WhisperModel(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val titleRu: String,
    val titleEn: String,
) {
    SMALL("ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", "Обычная", "Standard"),
    BASE("ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", "Лёгкая", "Light"),
    TINY("ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", "Минимальная", "Minimal");

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        val DEFAULT = SMALL
    }
}

sealed interface ModelState {
    data object Absent : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState {
        val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt() else 0
    }
    data object Verifying : ModelState
    data class Ready(val file: File) : ModelState
    data class Failed(val reason: ModelFailure) : ModelState
}

enum class ModelFailure { NO_SPACE, NETWORK, CORRUPTED }

/**
 * Модели на устройстве (`filesDir/whisper`): докачка с места обрыва (`.part` + Range), проверка
 * SHA-256, удаление. В APK модели нет — она скачивается только по выбору пользователя.
 */
class WhisperModelStore(
    private val dir: File,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    private val _states = MutableStateFlow(scan())
    val states: StateFlow<Map<WhisperModel, ModelState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<WhisperModel, Job>()

    fun readyFile(model: WhisperModel): File? = (states.value[model] as? ModelState.Ready)?.file

    /** Скачанная модель, начиная с предпочитаемой. */
    fun anyReady(preferred: WhisperModel): Pair<WhisperModel, File>? =
        (listOf(preferred) + WhisperModel.entries).firstNotNullOfOrNull { m -> readyFile(m)?.let { m to it } }

    @Synchronized
    fun download(model: WhisperModel) {
        if (jobs[model]?.isActive == true || states.value[model] is ModelState.Ready) return
        jobs[model] = scope.launch(Dispatchers.IO) {
            val result = runCatching { fetch(model) }
            val state = result.fold({ it }, { e ->
                if (e is CancellationException) ModelState.Absent else ModelState.Failed(ModelFailure.NETWORK)
            })
            set(model, if (state is ModelState.Absent) scanOne(model) else state)
        }
    }

    @Synchronized
    fun cancel(model: WhisperModel) {
        jobs.remove(model)?.cancel()
    }

    fun delete(model: WhisperModel) {
        cancel(model)
        File(dir, model.fileName).delete()
        File(dir, model.fileName + PART).delete()
        set(model, ModelState.Absent)
    }

    private suspend fun fetch(model: WhisperModel): ModelState = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = File(dir, model.fileName + PART)
        val have = part.length()
        // Место: вся оставшаяся часть модели и запас на систему.
        if (freeBytes(dir) < model.sizeBytes - have + SPACE_MARGIN) return@withContext ModelState.Failed(ModelFailure.NO_SPACE)
        set(model, ModelState.Downloading(have, model.sizeBytes))
        val request = Request.Builder().url(model.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext ModelState.Failed(ModelFailure.NETWORK)
            // Сервер мог проигнорировать Range — тогда пишем с нуля.
            val append = have > 0 && response.code == 206
            val body = response.body ?: return@withContext ModelState.Failed(ModelFailure.NETWORK)
            RandomAccessFile(part, "rw").use { out ->
                if (!append) out.setLength(0)
                out.seek(out.length())
                var written = out.length()
                val buffer = ByteArray(256 * 1024)
                var lastReport = 0L
                body.byteStream().use { input ->
                    while (isActive) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport > REPORT_STEP) {
                            lastReport = written
                            set(model, ModelState.Downloading(written, model.sizeBytes))
                        }
                    }
                }
            }
        }
        set(model, ModelState.Verifying)
        if (part.length() != model.sizeBytes || sha256(part) != model.sha256) {
            part.delete()
            return@withContext ModelState.Failed(ModelFailure.CORRUPTED)
        }
        val target = File(dir, model.fileName)
        if (!part.renameTo(target)) return@withContext ModelState.Failed(ModelFailure.CORRUPTED)
        ModelState.Ready(target)
    }

    private fun scan(): Map<WhisperModel, ModelState> = WhisperModel.entries.associateWith(::scanOne)

    private fun scanOne(model: WhisperModel): ModelState {
        val file = File(dir, model.fileName)
        // Готовая модель проверена при загрузке; здесь — только размер, чтобы не хешировать 190 МБ на старте.
        return if (file.isFile && file.length() == model.sizeBytes) ModelState.Ready(file) else ModelState.Absent
    }

    private fun set(model: WhisperModel, state: ModelState) = _states.update { it + (model to state) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PART = ".part"
        const val SPACE_MARGIN = 200L * 1024 * 1024
        const val REPORT_STEP = 2L * 1024 * 1024
    }
}

/**
 * Чего ждать от устройства. Whisper на телефоне работает на ядрах CPU (NEON): памяти нужно примерно
 * размер модели ×2, а скорость упирается в число «больших» ядер. Слабому устройству предлагаем
 * модель полегче вместо той, что будет считать медленнее самого видео.
 */
object WhisperDeviceProfile {
    private const val GB = 1024L * 1024 * 1024

    fun recommended(totalRamBytes: Long, cores: Int, arm64: Boolean): WhisperModel = when {
        !arm64 || totalRamBytes < 3 * GB || cores < 6 -> WhisperModel.TINY
        totalRamBytes < 6 * GB || cores < 8 -> WhisperModel.BASE
        else -> WhisperModel.SMALL
    }

    /** Модель тяжелее рекомендованной — предупредить перед загрузкой. */
    fun isTooHeavy(model: WhisperModel, recommended: WhisperModel): Boolean = model.ordinal < recommended.ordinal

    /** Потоки: не больше числа ядер минус одно (интерфейс и плеер), не больше 6 — дальше прироста нет. */
    fun threads(cores: Int): Int = (cores - 1).coerceIn(1, 6)
}
