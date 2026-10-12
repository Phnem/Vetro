package com.example.myapplication.audiobooks.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.myapplication.audiobooks.domain.enrichment.BookWorkEnrichment
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.sync.supabase.AttachmentSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Смена обложки книги руками: «обновить» ищет другую картинку, «импорт» берёт файл пользователя.
 *
 * Обложка — строка `cover_url` произведения, она же уходит в облако вместе с книгой. Поэтому
 * импортированный файл не может остаться путём на этом телефоне: он сжимается и грузится в R2, а в
 * `cover_url` пишется `collection-attachment://<id>` — такую ссылку читает любое устройство
 * аккаунта (см. `CloudAttachmentFetcher`). Нет аккаунта или сети — обложка остаётся только здесь,
 * файлом `file://`, и пользователь узнаёт об этом из [Result.ChangedOnDevice].
 */
class BookCoverManager(
    private val context: Context,
    private val repository: AudiobookRepository,
    private val search: AudiobookSearch,
    private val enrichment: BookWorkEnrichment,
    private val attachments: AttachmentSyncManager,
    private val client: OkHttpClient,
) {
    sealed interface Result {
        /** Обложка заменена (адрес сайта или файл в облаке). */
        data class Changed(val coverUrl: String) : Result

        /** Импорт удался, но облако недоступно: обложка видна только на этом устройстве. */
        data class ChangedOnDevice(val coverUrl: String) : Result

        /** Других подходящих картинок нет; следующее нажатие начнёт поиск заново. */
        data object NothingNew : Result

        data object Failed : Result
    }

    /** Адреса, которые по произведению уже показывали в этом запуске: «обновить» не ходит по кругу. */
    private val shown = mutableMapOf<String, MutableSet<String>>()

    /** Другая обложка: сначала те же книги на сайтах-источниках, затем Open Library. */
    suspend fun reload(workId: WorkId): Result = withContext(Dispatchers.IO) {
        val info = repository.coverInfo(workId) ?: return@withContext Result.Failed
        val seen = shown.getOrPut(workId.value) { mutableSetOf() }
        info.coverUrl?.let { seen += it }

        val candidates = runCatching {
            search.sameWork(info.title, info.authors).mapNotNull { it.coverUrl } +
                listOfNotNull(enrichment.coverUrl(info.title, info.authors))
        }.onFailure { Log.w(TAG, "cover search failed: ${it.message}") }.getOrDefault(emptyList())

        val next = pickNextCover(candidates, seen, MAX_CHECKS) { url ->
            // Негодный адрес запоминаем сразу: иначе каждое нажатие проверяло бы его заново.
            isImage(url).also { if (!it) seen += url }
        }
        if (next == null) {
            // Круг пройден: следующее нажатие начнёт его заново с текущей обложки.
            seen.clear()
            return@withContext Result.NothingNew
        }
        seen += next
        repository.setCover(workId, next)
        release(info.coverUrl)
        Result.Changed(next)
    }

    /** Обложка из файла пользователя: сжатие, загрузка в облако, запись в `cover_url`. */
    suspend fun import(workId: WorkId, source: Uri): Result = withContext(Dispatchers.IO) {
        val raw = runCatching { context.contentResolver.openInputStream(source)?.use { it.readBytes() } }
            .onFailure { Log.w(TAG, "cover read failed: ${it.message}") }
            .getOrNull()
        if (raw == null || raw.isEmpty() || raw.size > MAX_IMPORT_BYTES || !looksLikeImage(raw)) {
            return@withContext Result.Failed
        }
        val previous = repository.coverInfo(workId)?.coverUrl
        val webp = attachments.compressForUpload(raw)

        val cloud = attachments.uploadStandaloneImage(workId.value, webp)
        if (cloud != null) {
            repository.setCover(workId, cloud)
            release(previous, keep = cloud)
            return@withContext Result.Changed(cloud)
        }
        val file = File(context.filesDir, "$LOCAL_DIR/${workId.value}-${System.currentTimeMillis()}.webp")
        val saved = runCatching { file.parentFile?.mkdirs(); file.writeBytes(webp) }.isSuccess
        if (!saved) return@withContext Result.Failed
        val url = Uri.fromFile(file).toString()
        repository.setCover(workId, url)
        release(previous, keep = url)
        Result.ChangedOnDevice(url)
    }

    /** Прежняя обложка осиротела: свой файл на устройстве и своя копия в R2 больше не нужны. */
    private suspend fun release(previous: String?, keep: String? = null) {
        if (previous == null || previous == keep) return
        when {
            previous.startsWith(CLOUD_PREFIX) -> runCatching { attachments.deleteAttachment(previous) }
            previous.startsWith("file:") -> runCatching {
                val file = File(requireNotNull(Uri.parse(previous).path))
                if (file.parentFile?.name == File(LOCAL_DIR).name) file.delete()
            }
        }
    }

    private fun isImage(url: String): Boolean = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            response.isSuccessful && body?.contentType()?.type == "image" && body.contentLength() != 0L
        }
    }.getOrDefault(false)

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private companion object {
        const val TAG = "BookCover"
        const val CLOUD_PREFIX = "collection-attachment://"
        const val LOCAL_DIR = "audiobooks/covers"
        const val MAX_IMPORT_BYTES = 25 * 1024 * 1024

        /** Сколько адресов проверяем за одно нажатие: каждый — сетевой запрос. */
        const val MAX_CHECKS = 8
    }
}

/** Чистая часть выбора «другой обложки»: порядок кандидатов сохраняется, повторы и увиденное пропускаются. */
internal suspend fun pickNextCover(
    candidates: List<String>,
    seen: Set<String>,
    maxChecks: Int,
    usable: suspend (String) -> Boolean,
): String? {
    var checks = 0
    for (url in candidates.distinct()) {
        if (url in seen || !(url.startsWith("http://") || url.startsWith("https://"))) continue
        if (checks++ >= maxChecks) return null
        if (usable(url)) return url
    }
    return null
}
