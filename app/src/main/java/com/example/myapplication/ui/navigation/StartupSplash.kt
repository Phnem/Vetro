package com.example.myapplication.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.ui.splash.SplashViewModel
import com.example.myapplication.ui.splash.VetroSplashScreen
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.systemAppLanguage
import org.koin.androidx.compose.koinViewModel

/**
 * Сплэш — слой поверх графа, а не его маршрут: так первый экран (главная или вход) строится под
 * сплэшем, пока тот стоит, и outro открывает готовый экран. Раньше граф переходил со сплэша
 * на главную уже после outro, и между ними был чёрный кадр на время первой композиции главной.
 */
@Stable
class StartupSplashState(visible: Boolean, nextRoute: String?) {
    /** Сплэш на экране; пока true, пуши и оверлей обновления ждут. */
    var visible by mutableStateOf(visible)

    /** [HOME] или [WELCOME], когда сплэш закончил работу: с этого момента граф существует. */
    var nextRoute by mutableStateOf(nextRoute)

    companion object {
        const val HOME = "home"
        const val WELCOME = "welcome"

        val Saver = listSaver<StartupSplashState, Any?>(
            save = { listOf(it.visible, it.nextRoute) },
            restore = { StartupSplashState(it[0] as Boolean, it[1] as String?) },
        )
    }
}

@Composable
fun rememberStartupSplashState(): StartupSplashState =
    rememberSaveable(saver = StartupSplashState.Saver) { StartupSplashState(visible = true, nextRoute = null) }

@Composable
internal fun StartupSplashOverlay(
    state: StartupSplashState,
    contentReady: () -> Boolean,
) {
    val splashViewModel: SplashViewModel = koinViewModel()
    val splashState by splashViewModel.uiState.collectAsStateWithLifecycle()
    val splashStrings = getStrings(systemAppLanguage())
    val legacyFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        splashViewModel.onLegacyFolderSelected(uri)
    }
    VetroSplashScreen(
        uiState = splashState,
        migrationTitle = splashStrings.splashStorageMigrationTitle,
        migrationSubtitle = splashStrings.splashStorageMigrationSubtitle,
        jsonMigrationTitle = splashStrings.splashJsonMigrationTitle,
        jsonMigrationSubtitle = splashStrings.splashJsonMigrationSubtitle,
        legacyFolderTitle = splashStrings.splashLegacyFolderTitle,
        legacyFolderSubtitle = splashStrings.splashLegacyFolderSubtitle,
        legacyFolderAction = splashStrings.splashLegacyFolderAction,
        legacyFolderSkip = splashStrings.splashLegacyFolderSkip,
        cloudRestoreTitle = splashStrings.cloudRestoreTitle,
        cloudRestoreSubtitle = splashStrings.cloudRestoreSubtitle,
        onPickLegacyFolder = { legacyFolderLauncher.launch(null) },
        onSkipLegacyFolder = { splashViewModel.skipLegacyFolderMigration() },
        onBuildContent = { next ->
            state.nextRoute = if (next == StartupSplashState.HOME) StartupSplashState.HOME else StartupSplashState.WELCOME
        },
        contentReady = contentReady,
        onSplashComplete = { state.visible = false },
    )
}
