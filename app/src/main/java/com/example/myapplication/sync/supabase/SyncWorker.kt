package com.example.myapplication.sync.supabase

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val syncRepository: SyncRepository by inject()
    private val attachmentSyncManager: AttachmentSyncManager by inject()

    override suspend fun doWork(): Result {
        return try {
            val push = syncRepository.pushPendingChanges()
            val pull = syncRepository.pullRemoteChanges()
            val error = push.error ?: pull.error
            if (error != null) {
                // Push/pull не бросают, а возвращают ошибку — раньше воркер всё равно ставил метку
                // «синхронизировано только что», и панель показывала успех после сбоя.
                android.util.Log.w(TAG, "Background sync incomplete: $error")
                return if (error == NOT_SIGNED_IN || runAttemptCount >= 3) Result.success() else Result.retry()
            }
            // Отметка времени последней успешной синхронизации — читается панелью синхронизации
            // (nottif.kt). Фоновый воркер не проходит через SupabaseSyncCoordinator.syncNow,
            // поэтому пишем метку и здесь.
            applicationContext
                .getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_sync_time", System.currentTimeMillis())
                .apply()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Background sync failed: ${safeSyncError(e)}")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
        const val NOT_SIGNED_IN = "Not signed in"
    }
}
