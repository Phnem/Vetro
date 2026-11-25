package com.example.myapplication.sync.supabase

import com.example.myapplication.utils.runSuspendCatching
import android.util.Log
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.repository.ImageStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Восстанавливает обложки из облака (R2) во внутреннюю папку collection.
 *
 * Сценарий «Пропустить» на экране импорта: если пользователь авторизован и в БД есть
 * collection-attachment:// — скачиваем, конвертируем в WebP и сохраняем локально.
 */
class CollectionImageRestoreCoordinator(
    private val localDataSource: AnimeLocalDataSource,
    private val imageStorage: ImageStorageRepository,
    private val attachmentSyncManager: AttachmentSyncManager,
    private val syncRepository: SyncRepository,
    private val authRepository: AuthRepository,
) {
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    suspend fun restoreFromCloudIfNeeded(): Int = withContext(Dispatchers.IO) {
        if (!authRepository.hasToken() || authRepository.isGuest) {
            Log.d(TAG, "Cloud restore skipped: user not signed in")
            return@withContext 0
        }

        _isRestoring.value = true
        try {
            runSuspendCatching { syncRepository.pullRemoteChanges() }
                .onFailure { error ->
                    Log.w(TAG, "Pull before cloud restore failed: ${safeSyncError(error)}")
                }

            var restored = 0
            for (anime in localDataSource.getAllAnimeList()) {
                val cloudUrl = anime.imageFileName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (!cloudUrl.startsWith(CLOUD_ATTACHMENT_PREFIX)) continue
                if (imageStorage.hasLocalImage(cloudUrl)) continue

                val localFileName = attachmentSyncManager.downloadAndImportToCollection(anime.id, cloudUrl)
                if (localFileName != null) {
                    restored++
                    Log.d(TAG, "Restored cover for ${anime.id} -> $localFileName")
                } else {
                    Log.w(TAG, "Failed to restore cover for ${anime.id} from $cloudUrl")
                }
            }

            Log.d(TAG, "Cloud restore finished: $restored image(s)")
            restored
        } finally {
            _isRestoring.value = false
        }
    }

    suspend fun hasRestorableCloudImages(): Boolean = withContext(Dispatchers.IO) {
        if (!authRepository.hasToken() || authRepository.isGuest) return@withContext false
        localDataSource.getAllAnimeList().any { anime ->
            val path = anime.imageFileName?.trim().orEmpty()
            path.startsWith(CLOUD_ATTACHMENT_PREFIX) && !imageStorage.hasLocalImage(path)
        }
    }

    private companion object {
        private const val TAG = "CollectionImageRestore"
        private const val CLOUD_ATTACHMENT_PREFIX = "collection-attachment://"
    }
}
