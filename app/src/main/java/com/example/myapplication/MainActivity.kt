package com.example.myapplication

import android.content.Intent
import android.graphics.Color as AndroidGraphicsColor
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.data.models.AppTheme
import com.example.myapplication.sync.ExternalListSyncCoordinator
import com.example.myapplication.ui.navigation.AppNavGraph
import com.example.myapplication.ui.navigation.isDeepLinkReady
import com.example.myapplication.ui.navigation.isSplashDestination
import com.example.myapplication.ui.navigation.navigateToDetails
import com.example.myapplication.notifications.ACTION_OPEN_ANIME
import com.example.myapplication.notifications.EXTRA_OPEN_ANIME_ID
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.myapplication.sync.supabase.SupabaseAuthDeeplinkHandler
import io.github.jan.supabase.SupabaseClient
import com.example.myapplication.ui.settings.UpdateChangelogSheet
import com.example.myapplication.ui.debug.FpsOverlay
import com.example.myapplication.ui.shared.LocalAdaptiveGlassEnabled
import com.example.myapplication.ui.shared.LocalModernUi
import com.example.myapplication.ui.shared.LocalStagedMorphOrigin
import com.example.myapplication.ui.shared.LocalWorkspaceSearch
import com.example.myapplication.ui.shared.WorkspaceSearchState
import com.example.myapplication.ui.shared.StagedMorphOriginState
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.example.myapplication.ui.settings.SettingsOverlayMotion
import com.example.myapplication.ui.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val externalListSyncCoordinator: ExternalListSyncCoordinator by inject()
    private val supabaseClient: SupabaseClient by inject()

    /** id тайтла из тапа по пушу «вышла новая серия»; открываем Details, когда граф готов. */
    private val pendingAnimeId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                AndroidGraphicsColor.TRANSPARENT,
                AndroidGraphicsColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidGraphicsColor.TRANSPARENT,
                AndroidGraphicsColor.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            // Иначе система может нарисовать непрозрачный скрим в status bar
            // поверх edge-to-edge контента при открытых шторках.
            window.disableStatusBarContrastEnforced()
        }
        handleSupabaseAuthDeeplink(intent)
        handleExternalListOAuthIntent(intent)
        handleOpenAnimeIntent(intent)

        setContent {
            val isSystemDark = isSystemInDarkTheme()
            val context = LocalContext.current

            val settingsViewModel: SettingsViewModel =
                koinViewModel(viewModelStoreOwner = this@MainActivity)
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val startupOverlayEligible by settingsViewModel.startupUpdateOverlayEligible.collectAsStateWithLifecycle()

            val installPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                settingsViewModel.onReturnedFromInstallSettings(context)
            }

            val useDarkTheme = when (settingsState.theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemDark
            }

            OneUiTheme(darkTheme = useDarkTheme) {
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !useDarkTheme
                        isAppearanceLightNavigationBars = !useDarkTheme
                    }
                }
                val navController = rememberNavController()
                val navEntry by navController.currentBackStackEntryAsState()
                val splashDestId = navEntry?.destination?.id

                // Тап по пушу «вышла новая серия» → Details тайтла. Ждём, пока граф
                // уйдёт со сплэша/логина: раньше push'ить Details некуда.
                val openAnimeId by pendingAnimeId.collectAsStateWithLifecycle()
                LaunchedEffect(openAnimeId, splashDestId) {
                    val animeId = openAnimeId ?: return@LaunchedEffect
                    val destination = navEntry?.destination
                    if (!destination.isDeepLinkReady()) return@LaunchedEffect
                    pendingAnimeId.value = null
                    navController.navigateToDetails(animeId)
                }

                var showStartupUpdateOverlay by remember { mutableStateOf(false) }
                LaunchedEffect(startupOverlayEligible, splashDestId) {
                    val onSplashScreen = navEntry?.destination.isSplashDestination()
                    val shouldOffer = startupOverlayEligible && !onSplashScreen
                    if (!startupOverlayEligible) {
                        showStartupUpdateOverlay = false
                    } else if (shouldOffer) {
                        showStartupUpdateOverlay = true
                    }
                }

                fun dismissStartupUpdateOverlay() {
                    showStartupUpdateOverlay = false
                    settingsViewModel.dismissStartupUpdateOverlayPersisted()
                }

                BackHandler(enabled = showStartupUpdateOverlay) {
                    dismissStartupUpdateOverlay()
                }

                val overlayVisible = showStartupUpdateOverlay

                val stagedMorphOrigin = remember { StagedMorphOriginState() }
                val workspaceSearch = remember { WorkspaceSearchState() }
                CompositionLocalProvider(
                    LocalAdaptiveGlassEnabled provides settingsState.devAdaptiveGlassScroll,
                    LocalModernUi provides settingsState.modernUi,
                    LocalStagedMorphOrigin provides stagedMorphOrigin,
                    LocalWorkspaceSearch provides workspaceSearch,
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AppNavGraph(
                        navController = navController,
                        settingsViewModel = settingsViewModel,
                    )

                    AnimatedVisibility(
                        visible = overlayVisible,
                        enter = SettingsOverlayMotion.scrimFadeIn,
                        exit = SettingsOverlayMotion.scrimFadeOut,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { dismissStartupUpdateOverlay() },
                        )
                    }
                    AnimatedVisibility(
                        visible = overlayVisible,
                        enter = SettingsOverlayMotion.panelFadeInScaleIn(),
                        exit = SettingsOverlayMotion.panelFadeOutScaleOut(),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .wrapContentHeight()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) { },
                            ) {
                                UpdateChangelogSheet(
                                    viewModel = settingsViewModel,
                                    onDismiss = { dismissStartupUpdateOverlay() },
                                    installPermissionLauncher = installPermissionLauncher,
                                    sharedModifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    if (settingsState.devFpsOverlay) {
                        FpsOverlay(modifier = Modifier.align(Alignment.TopStart))
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSupabaseAuthDeeplink(intent)
        handleExternalListOAuthIntent(intent)
        handleOpenAnimeIntent(intent)
    }

    /** Тап по пушу о новой серии: запоминаем id, навигация — когда NavHost дойдёт до Home. */
    private fun handleOpenAnimeIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_ANIME) return
        val animeId = intent.getStringExtra(EXTRA_OPEN_ANIME_ID) ?: return
        pendingAnimeId.value = animeId
        // Иначе поворот экрана/возврат в активити повторно открыл бы тот же тайтл.
        intent.removeExtra(EXTRA_OPEN_ANIME_ID)
    }

    private fun handleSupabaseAuthDeeplink(intent: Intent?) {
        SupabaseAuthDeeplinkHandler.handle(
            lifecycleOwner = this,
            supabase = supabaseClient,
            intent = intent,
            onConsumed = ::clearSupabaseAuthIntent,
        )
    }

    private fun clearSupabaseAuthIntent() {
        val current = intent ?: return
        val uri = current.data ?: return
        if (!SupabaseAuthDeeplinkHandler.isAuthCallback(uri)) return
        setIntent(Intent(current).apply { data = null })
    }

    private fun handleExternalListOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        externalListSyncCoordinator.handleOAuthRedirect(uri)
    }
}

@Suppress("DEPRECATION")
private fun Window.disableStatusBarContrastEnforced() {
    isStatusBarContrastEnforced = false
}
