package com.example.myapplication.manga.download

import com.example.myapplication.network.AppStoreJson
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.source.MangaSourceEngine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Скачивание одной главы в фоне: пользователь может уйти с экрана или свернуть приложение.
 *
 * Глава передаётся целиком сериализованной — воркеру нужен и ключ тайтла, и источник, а лезть за
 * ними в кэш оглавления (который к моменту запуска мог протухнуть) незачем.
 */
class MangaDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val engine: MangaSourceEngine by inject()
    private val downloader: MangaChapterDownloader by inject()

    override suspend fun doWork(): Result {
        val raw = inputData.getString(KEY_CHAPTER) ?: return Result.failure()
        val chapter = runCatching { json.decodeFromString<MangaChapter>(raw) }.getOrNull()
            ?: return Result.failure()

        val pages = engine.pages(chapter)
        if (pages.isEmpty()) {
            Log.w(TAG, "No pages for chapter ${chapter.key}")
            return Result.failure()
        }
        return if (downloader.download(chapter, pages)) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "MangaDownloadWorker"
        private const val KEY_CHAPTER = "chapter"
        private val json = AppStoreJson

        /**
         * Уникальная работа на главу с политикой KEEP: повторный тап по «скачать» не плодит
         * вторую загрузку той же главы.
         */
        fun enqueue(context: Context, chapter: MangaChapter) {
            val request = OneTimeWorkRequestBuilder<MangaDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_CHAPTER, json.encodeToString(MangaChapter.serializer(), chapter))
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(chapter),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, chapter: MangaChapter) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(chapter))
        }

        private fun workName(chapter: MangaChapter) =
            "manga_download_${chapter.sourceId.value}_${chapter.key}"
    }
}
