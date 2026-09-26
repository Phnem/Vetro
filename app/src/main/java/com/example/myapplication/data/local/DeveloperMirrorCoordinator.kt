package com.example.myapplication.data.local

import com.example.myapplication.AppScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeveloperMirrorCoordinator(
    settingsDataStore: DataStore<Preferences>,
    private val exporter: VetroPublicDbExporter,
    appScope: AppScope,
) {
    // Общая область процесса (AppScope) вместо своей CoroutineScope(SupervisorJob() + IO).
    private val scope: CoroutineScope = appScope
    private val exportMutex = Mutex()
    @Volatile
    private var mirrorEnabled: Boolean = false

    init {
        scope.launch {
            settingsDataStore.data
                .map { it[KEY_DEV_MIRROR_DB] ?: false }
                .distinctUntilChanged()
                .collect { mirrorEnabled = it }
        }
    }

    fun requestExportIfEnabled() {
        if (!mirrorEnabled) return
        scope.launch {
            exportMutex.withLock {
                if (mirrorEnabled) {
                    exporter.exportInternalDbToPublicFolder()
                }
            }
        }
    }

    private companion object {
        private val KEY_DEV_MIRROR_DB = booleanPreferencesKey("dev_mirror_db_to_documents")
    }
}
