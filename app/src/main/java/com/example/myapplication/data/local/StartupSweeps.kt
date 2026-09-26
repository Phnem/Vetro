package com.example.myapplication.data.local

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.example.myapplication.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * «Подметание» хранилища на старте: докопировать обложки из старой публичной папки, из MediaStore и
 * из сохранённой SAF-папки, пережать крупные обложки.
 *
 * Раньше всё это шло на КАЖДОМ холодном старте и сплэш ждал конца: чтение всей коллекции из БД,
 * stat на каждую обложку, запрос к MediaStore и обход SAF-дерева. Теперь полный проход блокирует
 * сплэш один раз (первый запуск этой версии или апгрейд со старой), дальше сплэш его не ждёт, а
 * повторная проверка идёт в фоне после старта — картинки, подложенные в старую папку, всё равно
 * подтянутся, просто не ценой запуска.
 */
class StartupSweeps(
    private val dataStore: DataStore<Preferences>,
    private val legacyStorageMigrator: LegacyStorageMigrator,
    private val legacyCollectionSafMigrator: LegacyCollectionSafMigrator,
    private val imageCompressionMigrator: ImageCompressionMigrator,
    private val appScope: AppScope,
) {
    /** Полный проход уже был — сплэшу ждать нечего. */
    suspend fun isDone(): Boolean = dataStore.data.first()[SWEEPS_DONE] == true

    suspend fun markDone() {
        dataStore.edit { it[SWEEPS_DONE] = true }
    }

    /** Повторная проверка после старта: не конкурирует с первой отрисовкой главной. */
    fun scheduleBackgroundRecheck() {
        appScope.launch {
            delay(RECHECK_DELAY_MS)
            try {
                legacyStorageMigrator.migrateIfNeeded()
                legacyCollectionSafMigrator.migrateAllAvailableSources()
                imageCompressionMigrator.compressExistingImages()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "background storage recheck failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "StartupSweeps"
        /** Версия в имени: изменится набор проходов — полный проход один раз прогонится заново. */
        val SWEEPS_DONE = booleanPreferencesKey("startup_sweeps_done_v1")
        const val RECHECK_DELAY_MS = 15_000L
    }
}
