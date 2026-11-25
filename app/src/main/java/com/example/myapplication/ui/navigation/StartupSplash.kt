package com.example.myapplication.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.local.DevPreferencesKeys
import com.example.myapplication.ui.splash.SplashViewModel
import com.example.myapplication.ui.splash.VetroSplashScreen
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.systemAppLanguage
import com.phnem.vetro.BuildConfig
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

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
    val folderRejected by splashViewModel.folderRejected.collectAsStateWithLifecycle()
    val rejectedSubtitle = if (systemAppLanguage() == com.example.myapplication.network.AppLanguage.RU) {
        "В этой папке нет Vetro/collection. Выберите другую папку."
    } else {
        "There is no Vetro/collection in that folder. Please choose another one."
    }
    val legacyFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        splashViewModel.onLegacyFolderSelected(uri)
    }
    // Какая заставка и с какой скоростью — решают dev-флаг и версия сборки; пока они читаются
    // (миллисекунды), держим фон темы. Первый запуск сборки (установка или обновление) — целиком,
    // дальше вдвое быстрее. Отметку пишем сразу: оборванный на середине показ второй раз не повторяем.
    val settingsStore: DataStore<Preferences> = koinInject(qualifier = named("settings"))
    val plan by produceState<SplashPlan?>(initialValue = null) {
        val prefs = settingsStore.data.first()
        val repeat = prefs[DevPreferencesKeys.SPLASH_SEEN_VERSION] == BuildConfig.VERSION_CODE
        value = SplashPlan(legacy = prefs[DevPreferencesKeys.LEGACY_SPLASH] ?: false, repeatLaunch = repeat)
        if (!repeat) settingsStore.edit { it[DevPreferencesKeys.SPLASH_SEEN_VERSION] = BuildConfig.VERSION_CODE }
    }
    val resolved = plan
    if (resolved == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    VetroSplashScreen(
        uiState = splashState,
        migrationTitle = splashStrings.splashStorageMigrationTitle,
        migrationSubtitle = splashStrings.splashStorageMigrationSubtitle,
        jsonMigrationTitle = splashStrings.splashJsonMigrationTitle,
        jsonMigrationSubtitle = splashStrings.splashJsonMigrationSubtitle,
        legacyFolderTitle = splashStrings.splashLegacyFolderTitle,
        legacyFolderSubtitle = if (folderRejected) rejectedSubtitle else splashStrings.splashLegacyFolderSubtitle,
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
        useLegacyAnimation = resolved.legacy,
        speed = if (resolved.repeatLaunch) RepeatLaunchSpeed else 1f,
    )
}

/** Во сколько раз заставка быстрее при повторных запусках той же сборки. */
private const val RepeatLaunchSpeed = 2f

private class SplashPlan(val legacy: Boolean, val repeatLaunch: Boolean)
