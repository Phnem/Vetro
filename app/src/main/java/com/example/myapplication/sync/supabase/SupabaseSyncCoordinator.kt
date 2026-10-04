package com.example.myapplication.sync.supabase

import com.example.myapplication.domain.BackgroundSchedule
import com.example.myapplication.AppScope
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction

class SupabaseSyncCoordinator(
    private val context: Context,
    private val syncRepository: SyncRepository,
    private val authRepository: AuthRepository,
    private val supabase: io.github.jan.supabase.SupabaseClient,
    private val collectionImageRestoreCoordinator: CollectionImageRestoreCoordinator,
    private val apiKeySyncRepository: ApiKeySyncRepository,
    private val progressSyncRepository: ProgressSyncRepository,
    private val audiobookSyncRepository: AudiobookSyncRepository,
    appScope: AppScope,
) {
    private val workManager = WorkManager.getInstance(context)
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> get() = _isSyncing
    private val _lastSyncMessage = MutableStateFlow<String?>(null)
    val lastSyncMessage: StateFlow<String?> get() = _lastSyncMessage
    // SupervisorJob: без него первое необработанное исключение в любом из launch'ей отменяло
    // весь scope, и подписка на вход/выход пользователя молча умирала до перезапуска процесса.
    // Общая область процесса (AppScope) вместо своей CoroutineScope(SupervisorJob() + IO).
    private val scope: CoroutineScope = appScope
    private var realtimeSyncJob: kotlinx.coroutines.Job? = null
    private var realtimeChannelJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch {
            authRepository.isUserSignedIn.collect { signedIn ->
                if (signedIn && !authRepository.isGuest) {
                    schedulePeriodicSync()
                    syncNow(includeCloudImageRestore = true)
                    ensureRealtimeSubscription()
                } else {
                    // Гостю синхронизировать нечего: раньше периодика всё равно будила воркер
                    // каждые 15 минут, и тот выходил с «не вошёл».
                    workManager.cancelUniqueWork(PERIODIC_SYNC_WORK)
                    realtimeChannelJob?.cancel()
                    realtimeChannelJob = null
                }
            }
        }
    }

    private fun ensureRealtimeSubscription() {
        if (realtimeChannelJob?.isActive == true) return
        realtimeChannelJob = scope.launch {
            setupRealtime()
        }
    }

    private suspend fun setupRealtime() {
        try {
            val channel = supabase.channel("public:anime")
            val animeChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "anime"
            }
            
            supabase.realtime.connect()
            channel.subscribe()
            
            animeChanges.collect {
                realtimeSyncJob?.cancel()
                realtimeSyncJob = scope.launch {
                    kotlinx.coroutines.delay(2_000)
                    syncNow()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Realtime setup failed: ${safeSyncError(e)}")
        }
    }

    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Час, а не 15 минут: изменения с других устройств и так приходят через Realtime, пока
        // приложение открыто; периодика — страховка для фона. UPDATE переводит на новый период
        // и уже поставленную работу (KEEP оставил бы у всех старые 15 минут).
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(BackgroundSchedule.CLOUD_SYNC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }

    fun syncNow(includeCloudImageRestore: Boolean = false) {
        scope.launch {
            if (_isSyncing.value) return@launch
            if (authRepository.isGuest || authRepository.currentUserId == null) return@launch
            _isSyncing.value = true
            try {
                val userId = authRepository.currentUserId
                if (includeCloudImageRestore && userId != null) {
                    syncRepository.requeueLocalUploadIfCloudEmpty(userId)
                }
                val push = syncRepository.pushPendingChanges()
                val pull = syncRepository.pullRemoteChanges()
                // E2EE-синк ключей AI Connect (best-effort, не влияет на статус синка коллекции).
                apiKeySyncRepository.sync()
                // Прогресс просмотра/чтения — тоже best-effort и после коллекции: строки прогресса
                // ссылаются на тайтлы по id, и приезжать им логично уже к подтянутой коллекции.
                progressSyncRepository.sync()
                // Аудиокниги — свои таблицы, тоже best-effort.
                audiobookSyncRepository.sync()
                if (includeCloudImageRestore) {
                    collectionImageRestoreCoordinator.restoreFromCloudIfNeeded()
                }
                _lastSyncMessage.value = when {
                    push.error != null -> "Upload: ${push.error}"
                    pull.error != null -> "Download: ${pull.error}"
                    push.pushedCount > 0 || pull.pulledCount > 0 ->
                        "Synced ↑${push.pushedCount} ↓${pull.pulledCount}"
                    else -> null
                }
                // Отметка времени последней УСПЕШНОЙ синхронизации — читается панелью
                // синхронизации (nottif.kt → "sync_prefs"/"last_sync_time"). Раньше не писалась
                // нигде, поэтому дата/время всегда показывались как «никогда».
                if (push.error == null && pull.error == null) {
                    context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("last_sync_time", System.currentTimeMillis())
                        .apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeError = safeSyncError(e)
                android.util.Log.e(TAG, "Sync failed: $safeError")
                _lastSyncMessage.value = safeError
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SupabaseSync"
        const val PERIODIC_SYNC_WORK = "SupabasePeriodicSync"
    }
}
