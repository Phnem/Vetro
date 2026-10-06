package com.example.myapplication.ui.splash

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.sync.supabase.AuthRepository
import com.example.myapplication.data.local.ImageCompressionMigrator
import com.example.myapplication.data.local.LegacyCollectionSafMigrator
import com.example.myapplication.data.local.LegacyStorageMigrator
import com.example.myapplication.data.local.MigrationManager
import com.example.myapplication.data.local.StartupSweeps
import com.example.myapplication.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SplashState {
    data object Loading : SplashState
    data object MigratingStorage : SplashState
    data object MigratingJson : SplashState
    data object AwaitingLegacyFolder : SplashState
    data object ImportingLegacyFolder : SplashState
    data object RestoringFromCloud : SplashState
    data class Completed(val nextRoute: String) : SplashState
}

class SplashViewModel(
    private val legacyStorageMigrator: LegacyStorageMigrator,
    private val legacyCollectionSafMigrator: LegacyCollectionSafMigrator,
    private val migrationManager: MigrationManager,
    private val authRepository: AuthRepository,
    private val appUpdateManager: AppUpdateManager,
    private val imageCompressionMigrator: ImageCompressionMigrator,
    private val startupSweeps: StartupSweeps,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SplashState>(SplashState.Loading)
    val uiState = _uiState.asStateFlow()

    // true только если в ЭТОМ запуске реально прошла миграция со старой версии (storage/JSON).
    // Плашку «Перенос обложек» показываем лишь тогда — не при обычной/облачно-наполненной БД.
    private var isLegacyUpgrade = false

    init {
        startAppInitialization()
    }

    fun onLegacyFolderSelected(uri: Uri?) {
        if (uri == null) {
            if (_uiState.value is SplashState.AwaitingLegacyFolder) {
                _uiState.update { SplashState.AwaitingLegacyFolder }
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { SplashState.ImportingLegacyFolder }
            withContext(Dispatchers.IO) {
                legacyCollectionSafMigrator.saveTreeUriAndCopy(uri)
                imageCompressionMigrator.compressExistingImages()
            }
            legacyCollectionSafMigrator.markCoverImportResolved()
            finishStartup(legacyFolderPromptDismissed = true)
        }
    }

    fun skipLegacyFolderMigration() {
        viewModelScope.launch {
            legacyCollectionSafMigrator.markCoverImportResolved()
            finishStartup(forceWithoutImages = true)
        }
    }

    private fun startAppInitialization() {
        // Весь старт — на IO: проверки ниже листают папки и считают строки в БД, а раньше шли
        // прямо на главном потоке, пока тот рисовал первый кадр сплэша.
        viewModelScope.launch(Dispatchers.IO) {
            // Полный проход по хранилищу уже был — сплэш его не ждёт, проверка уходит в фон.
            // JSON-миграция остаётся блокирующей: это данные коллекции, а не картинки.
            if (startupSweeps.isDone()) {
                val pendingJsonMigration = migrationManager.needsJsonMigration()
                if (pendingJsonMigration) {
                    _uiState.update { SplashState.MigratingJson }
                    migrationManager.runMigration()
                    isLegacyUpgrade = true
                    runFullSweeps()
                    return@launch
                }
                startupSweeps.scheduleBackgroundRecheck()
                finishStartup()
                return@launch
            }

            val pendingStorageMigration = legacyStorageMigrator.isPendingMigration()
            if (pendingStorageMigration) {
                _uiState.update { SplashState.MigratingStorage }
            }
            legacyStorageMigrator.migrateIfNeeded()

            val pendingJsonMigration = migrationManager.needsJsonMigration()
            if (pendingJsonMigration) {
                _uiState.update { SplashState.MigratingJson }
                migrationManager.runMigration()
            }

            // Апгрейд со старой версии = была миграция storage или JSON в этом запуске.
            isLegacyUpgrade = pendingStorageMigration || pendingJsonMigration
            runFullSweeps()
        }
    }

    /** Полный проход по хранилищу со сплэшем в ожидании — один раз на версию проходов. */
    private suspend fun runFullSweeps() {
        legacyCollectionSafMigrator.migrateAllAvailableSources()

        if (shouldPromptLegacyFolder()) {
            _uiState.update { SplashState.AwaitingLegacyFolder }
            return
        }

        imageCompressionMigrator.compressExistingImages()
        startupSweeps.markDone()
        finishStartup()
    }

    /**
     * Плашку «Перенос обложек» показываем только если: это апгрейд со старой версии,
     * пользователь ещё не разобрался с переносом, и локальных обложек реально не хватает.
     */
    private suspend fun shouldPromptLegacyFolder(): Boolean =
        isLegacyUpgrade &&
            !legacyCollectionSafMigrator.isCoverImportResolved() &&
            legacyCollectionSafMigrator.needsLegacyFolderAccess()

    private suspend fun finishStartup(
        forceWithoutImages: Boolean = false,
        legacyFolderPromptDismissed: Boolean = false,
    ) {
        if (
            !forceWithoutImages &&
            !legacyFolderPromptDismissed &&
            shouldPromptLegacyFolder()
        ) {
            _uiState.update { SplashState.AwaitingLegacyFolder }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { appUpdateManager.check(force = false) }
        }

        val route = if (authRepository.isGuest || authRepository.awaitSessionRestored()) "home" else "welcome"
        _uiState.update { SplashState.Completed(route) }
    }
}
