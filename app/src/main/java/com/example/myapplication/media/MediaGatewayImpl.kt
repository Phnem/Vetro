package com.example.myapplication.media

import com.example.myapplication.data.local.AppLanguagePrefs
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.media.download.MediaDownloadWorker
import com.example.myapplication.media.download.MediaJobBus
import com.example.myapplication.media.source.SourceEngine
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.PlaybackResolution
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.withoutPersistedSecrets
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.media.download.DownloadQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class MediaGatewayImpl(
    private val context: Context,
    private val sourceEngine: SourceEngine,
    private val settingsDataStore: DataStore<Preferences>,
) : MediaGateway {

    private val json = Json { encodeDefaults = true }

    override suspend fun resolvePlayback(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo?,
    ): PlaybackResolution {
        val language = currentLanguage()
        return try {
            sourceEngine.resolve(PlaybackRequest(anime, episodeNumber, seasonInfo, language))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "native resolve failed: ${error.message}")
            PlaybackResolution.Failure
        }
    }

    override suspend fun resolveBestVideo(hosters: List<VetroHoster>): VetroVideo? =
        sourceEngine.resolveBestVideo(hosters)

    override suspend fun enqueueDownload(
        video: VetroVideo,
        fallbackVideos: List<VetroVideo>,
        quality: DownloadQuality,
        outDir: File,
        animeId: String,
        episodeNumber: Int,
        durationSec: Int?,
    ): JobId {
        outDir.mkdirs()
        val jobId = UUID.randomUUID().toString()
        MediaJobBus.update(
            com.example.myapplication.media.download.MediaJobProgress(jobId, "queued")
        )

        val candidates = downloadableCandidates(video, fallbackVideos)
        val persistedCandidates = fitCandidatesToBudget(
            candidates.map(VetroVideo::withoutPersistedSecrets),
            CANDIDATES_BUDGET_BYTES,
        ) { json.encodeToString(it) }
        val data = workDataOf(
            MediaDownloadWorker.KEY_JOB_ID to jobId,
            MediaDownloadWorker.KEY_URL to video.url,
            MediaDownloadWorker.KEY_LABEL to video.label,
            MediaDownloadWorker.KEY_OUT_DIR to outDir.absolutePath,
            MediaDownloadWorker.KEY_EPISODE to episodeNumber,
            MediaDownloadWorker.KEY_ANIME_ID to animeId,
            MediaDownloadWorker.KEY_QUALITY to quality.label,
            MediaDownloadWorker.KEY_DURATION_SEC to (durationSec ?: -1),
            MediaDownloadWorker.KEY_HEADERS_JSON to json.encodeToString(
                video.withoutPersistedSecrets().headers
            ),
            MediaDownloadWorker.KEY_CANDIDATES_JSON to json.encodeToString(persistedCandidates),
        )
        val req = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(MediaDownloadWorker.WORK_NAME)
            .addTag(jobId)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${MediaDownloadWorker.WORK_NAME}_$jobId",
            ExistingWorkPolicy.REPLACE,
            req,
        )
        return JobId(jobId)
    }



    private suspend fun currentLanguage(): AppLanguage =
        settingsDataStore.data.map { prefs ->
            AppLanguagePrefs.from(prefs)
        }.first()

    companion object {
        /** Из 10 КБ входа задачи: остальное — URL, заголовки, путь, ключи. */
        private const val CANDIDATES_BUDGET_BYTES = 6_000

        private const val TAG = "MediaGateway"
    }
}
