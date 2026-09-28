package com.example.myapplication.audiobooks.text

import android.util.Log
import com.example.myapplication.audiobooks.domain.model.VariantId
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Текст книги, найденный для озвучки: откуда он и сам текст (display + главы). */
@Serializable
data class StoredText(
    val sourceId: String,
    val sourceName: String,
    val candidateId: String,
    val title: String,
    val language: String?,
    val display: String,
    val chapters: List<StoredChapter> = emptyList(),
    val savedAt: Long = 0L,
) {
    fun toBookText() = BookText(display, chapters.map { TextChapter(it.title, it.start) }, language)
}

@Serializable
data class StoredChapter(val title: String, val start: Int)

/** «Текста нет» тоже запоминается — чтобы не ходить по сайтам при каждом открытии меню. */
@Serializable
data class TextMiss(val at: Long, val mismatch: Boolean = false)

/** Обработанный блок звука: где и насколько хорошо совпал. */
@Serializable
data class AlignedBlock(val startMs: Long, val endMs: Long, val ratio: Float)

/**
 * Выравнивание одной озвучки с одним текстом. Действительно, пока совпадают [audioFingerprint],
 * [textFingerprint] и [algorithm]; иначе считается заново.
 */
@Serializable
data class AlignmentFile(
    val algorithm: Int,
    val audioFingerprint: String,
    val textFingerprint: String,
    val blocks: List<AlignedBlock> = emptyList(),
    val anchors: List<TextAnchor> = emptyList(),
    val cues: List<AlignedCue> = emptyList(),
    /** Первые блоки не нашли себя в тексте — это другой перевод или другая книга. */
    val mismatch: Boolean = false,
)

/**
 * Файлы синхронизации: `files/audiobooks/alignment/<озвучка>/text.json` и `alignment.json`.
 * Отдельно от распознанных субтитров «Создать на устройстве» — это разные вещи.
 */
class AlignmentStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun dir(variant: VariantId) = File(root, hash(variant.value))

    suspend fun text(variant: VariantId): StoredText? = read(File(dir(variant), TEXT), StoredText.serializer())

    suspend fun saveText(variant: VariantId, text: StoredText) {
        write(File(dir(variant), TEXT), StoredText.serializer(), text)
        File(dir(variant), MISS).delete()
    }

    suspend fun miss(variant: VariantId): TextMiss? = read(File(dir(variant), MISS), TextMiss.serializer())

    suspend fun saveMiss(variant: VariantId, miss: TextMiss) = write(File(dir(variant), MISS), TextMiss.serializer(), miss)

    /** Другой текст (выбранный вручную, «Искать снова»): старый и его выравнивание — прочь. */
    suspend fun forget(variant: VariantId) = withContext(Dispatchers.IO) { dir(variant).deleteRecursively() }

    suspend fun alignment(variant: VariantId): AlignmentFile? = read(File(dir(variant), ALIGNMENT), AlignmentFile.serializer())

    suspend fun saveAlignment(variant: VariantId, file: AlignmentFile) = write(File(dir(variant), ALIGNMENT), AlignmentFile.serializer(), file)

    private suspend fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse {
            // Повреждённый файл (оборванная запись) — не доверяем и считаем заново.
            Log.w(TAG, "corrupted ${file.name}, dropping: ${it.message}")
            file.delete()
            null
        }
    }

    private suspend fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    private companion object {
        const val TAG = "AlignmentStore"
        const val TEXT = "text.json"
        const val MISS = "miss.json"
        const val ALIGNMENT = "alignment.json"
    }
}
