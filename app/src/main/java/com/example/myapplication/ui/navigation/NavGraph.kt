package com.example.myapplication.ui.navigation

import com.example.myapplication.ui.details.SwipeBackGesture
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.ui.details.DetailsScreen
import com.example.myapplication.audiobooks.ui.details.BookDetailsScreen
import com.example.myapplication.ui.shared.theme.MotionTokens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute

import com.example.myapplication.WelcomeScreen
import com.example.myapplication.ui.addedit.AddEditScreen
import com.example.myapplication.ui.addedit.AddEditViewModel
import com.example.myapplication.ui.home.HomeScreen
import com.example.myapplication.ui.home.HomeViewModel
import com.example.myapplication.ui.inspect.InspectScreen
import com.example.myapplication.ui.inspect.InspectViewModel
import com.example.myapplication.ui.settings.SettingsScreen
import com.example.myapplication.ui.settings.SettingsViewModel
import com.example.myapplication.ui.workspace.WorkspaceScreen
import com.example.myapplication.audiobooks.ui.AudiobookPlayerHost
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.utils.getWelcomeStrings
import com.example.myapplication.utils.systemAppLanguage
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import org.koin.compose.koinInject

/**
 * Окно из меню ТТМ вырастает из гнезда «Ещё» и схлопывается обратно в него — это shared-bounds
 * морф самих экранов (см. WorkspaceDock и корни InspectScreen / AddEditScreen). Переход маршрута
 * отвечает только за то, что лежит ВНЕ морфа (фон и плавающие кнопки формы): это содержимое, и
 * по §5 спеки оно проявляется, когда оболочка почти раскрылась, а гаснет первым.
 */
private fun workspaceModalEnter() =
    fadeIn(
        animationSpec = MotionTokens.easeEnter(delayMillis = MotionTokens.WindowContentRevealDelayMillis),
    )

private fun workspaceModalExit() =
    fadeOut(animationSpec = MotionTokens.easeExit())

/**
 * Главная под окном из меню только притухает, без «вдавливания»: гнездо, в которое окно
 * схлопнется, лежит на ней, и масштаб главной сдвинул бы цель посреди полёта.
 */
private fun homeDimExit() =
    fadeOut(animationSpec = MotionTokens.easeExit(MotionTokens.DurationEmphasizedMillis), targetAlpha = 0.55f)

private fun homeDimPopEnter() =
    fadeIn(animationSpec = MotionTokens.easeEnter(MotionTokens.DurationStandardMillis), initialAlpha = 0.55f)

/**
 * «Вдавливание» главной под экраном поверх неё (физика IosSheetScaffold). Только масштаб, без
 * fade: прозрачность всей главной (стекло, блюр, offscreen-слои страниц) — это полноэкранный
 * буфер на каждом кадре перехода, от него и «назад», и открытие Details теряли кадры.
 */
private fun homeDepressExit() =
    scaleOut(
        targetScale = 0.92f,
        animationSpec = MotionTokens.sheetPresent(),
    )

private fun homeDepressPopEnter() =
    if (SwipeBackGesture.active) {
        // Свайп «назад» по экрану деталей ведёт переход за пальцем — нужна линейная кривая.
        scaleIn(initialScale = 0.92f, animationSpec = MotionTokens.gestureLinear(SWIPE_BACK_LINEAR_MS))
    } else {
        // Без fade — см. homeDepressExit. Кривая, а не пружина: системный жест «назад» ведёт
        // переход по времени, и у пружины остаток после отпускания полз её хвостом.
        scaleIn(
            initialScale = 0.92f,
            animationSpec = MotionTokens.tweenStandard(),
        )
    }

/** См. [SwipeBackGesture.LINEAR_MS]. */
private const val SWIPE_BACK_LINEAR_MS = SwipeBackGesture.LINEAR_MS

@OptIn(ExperimentalSharedTransitionApi::class)
// UnstableApi у media3 — маркер lint, а не Kotlin @RequiresOptIn: снимается androidx-аннотацией.
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun AppNavGraph(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    startupSplash: StartupSplashState,
) {
    val homeViewModel: HomeViewModel = koinViewModel()
    // Формы AddEdit и Inspect создаются, когда их маршрут открыт впервые, а не на сплэше: у обеих
    // в конструкторе горячие подписки на настройки. Владелец — тот же, что был (активити), поэтому
    // экземпляр по-прежнему один на все заходы.
    val activityViewModelOwner = checkNotNull(LocalViewModelStoreOwner.current)

    val context = LocalContext.current
    val authRepository: com.example.myapplication.sync.supabase.AuthRepository = koinInject()

    LaunchedEffect(Unit) {
        homeViewModel.scheduleBackgroundWork(context)
    }

    // Модальные переходы нужны только рабочей области: в старом доке у тех же экранов есть
    // пара для shared-bounds морфа. Флаг читается из StateFlow в момент перехода, а не подпиской —
    // граф не должен перекомпоновываться на каждое изменение настроек.
    fun workspaceModal(entry: NavBackStackEntry) =
        settingsViewModel.uiState.value.modernUi && entry.isWorkspaceModal()

    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
        // Граф появляется, когда сплэш решил, куда идти, — ещё под сплэшем (StartupSplash.kt).
        val nextRoute = startupSplash.nextRoute
        if (nextRoute != null) NavHost(
            navController = navController,
            startDestination = if (nextRoute == StartupSplashState.HOME) HomeRoute else WelcomeRoute,
        ) {
            composable<WelcomeRoute>(
                enterTransition = { fadeIn(animationSpec = MotionTokens.tweenEmphasized()) },
            ) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val isUserSignedIn by authRepository.isUserSignedIn.collectAsStateWithLifecycle(initialValue = false)
                val welcomeStrings = getWelcomeStrings(systemAppLanguage())

                LaunchedEffect(isUserSignedIn) {
                    if (isUserSignedIn) {
                        navController.navigateToHome()
                    }
                }
                WelcomeScreen(
                    strings = welcomeStrings,
                    onGoogleSignInClick = {
                        scope.launch {
                            val result = authRepository.signInWithGoogle(context)
                            if (result.isFailure) {
                                val msg = result.exceptionOrNull()?.message ?: "?"
                                android.widget.Toast.makeText(
                                    context,
                                    welcomeStrings.loginFailedFormat.format(msg),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onGithubSignInClick = {
                        scope.launch {
                            val result = authRepository.signInWithGithub()
                            if (result.isFailure) {
                                val msg = result.exceptionOrNull()?.message ?: "?"
                                android.widget.Toast.makeText(
                                    context,
                                    welcomeStrings.loginFailedFormat.format(msg),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onForgotPasswordClick = { email ->
                        scope.launch {
                            val result = authRepository.resetPasswordForEmail(email)
                            if (result.isSuccess) {
                                android.widget.Toast.makeText(
                                    context,
                                    welcomeStrings.resetPasswordSuccessFormat.format(email),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } else {
                                val msg = result.exceptionOrNull()?.message ?: "?"
                                android.widget.Toast.makeText(
                                    context,
                                    welcomeStrings.resetPasswordErrorFormat.format(msg),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onEmailSignInClick = { email, password ->
                        scope.launch {
                            val result = authRepository.signInWithEmail(email, password)
                            if (result.isFailure) {
                                val msg = result.exceptionOrNull()?.message ?: "?"
                                android.widget.Toast.makeText(
                                    context,
                                    welcomeStrings.loginFailedFormat.format(msg),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onGuestClick = { 
                        authRepository.signInAsGuest()
                        navController.navigateToHome() 
                    }
                )
            }

            composable<HomeRoute>(
                enterTransition = { fadeIn(animationSpec = MotionTokens.tweenEmphasized()) },
                // «Вдавливание» под деталями (физика IosSheetScaffold): фон уезжает назад
                // и слегка гаснет, при закрытии деталей — физично возвращается. Predictive
                // back сикает popEnter — возврат следует за пальцем.
                exitTransition = {
                    when {
                        targetState.destination.isDetailsDestination() -> homeDepressExit()
                        workspaceModal(targetState) -> homeDimExit()
                        else -> null
                    }
                },
                popEnterTransition = {
                    if (initialState.destination.isDetailsDestination()) {
                        homeDepressPopEnter()
                    } else if (workspaceModal(initialState)) {
                        homeDimPopEnter()
                    } else {
                        fadeIn(animationSpec = MotionTokens.tweenEmphasized())
                    }
                },
            ) {
                // ЕДИНСТВЕННАЯ точка чтения флага новой навигации: ниже по дереву его не знают.
                // Две навигации никогда не смонтированы одновременно — иначе задвоятся ключи
                // shared-element (иконки старого дока и экраны-цели).
                val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                if (settingsState.modernUi) {
                    WorkspaceScreen(
                        navController = navController,
                        homeViewModel = homeViewModel,
                        settingsViewModel = settingsViewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                    )
                } else {
                    HomeScreen(
                        navController = navController,
                        viewModel = homeViewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this,
                    )
                }
            }

            // Полноэкранные детали (iOS push): въезжают справа, назад — уезжают вправо.
            composable<DetailsRoute>(
                enterTransition = {
                    // Только сдвиг: окно деталей непрозрачное и наезжает поверх, fade лишь
                    // заставлял рисовать его через полноэкранный буфер.
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = MotionTokens.sheetOffset,
                    )
                },
                popExitTransition = {
                    if (SwipeBackGesture.active) {
                        // Окно идёт за пальцем 1:1 — линейно; см. SwipeBackGesture.
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = MotionTokens.gestureLinear(SWIPE_BACK_LINEAR_MS),
                        )
                    } else {
                        // Кривая, а не пружина — см. homeDepressPopEnter.
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = MotionTokens.tweenStandard(),
                        )
                    }
                },
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<DetailsRoute>()
                // Скругление краёв уезжающего окна (референс — Telegram): радиус растёт
                // по прогрессу перехода, predictive back сикает его вместе с жестом.
                // Читается в слое, а не в композиции: иначе весь Details пересобирался на каждом
                // кадре жеста «назад».
                val windowCorner = transition.animateDp(label = "detailsWindowCorner") { state ->
                    if (state == EnterExitState.Visible) 0.dp else 42.dp
                }
                DetailsScreen(
                    navController = navController,
                    animeId = route.animeId,
                    openEpisodes = route.openEpisodes,
                    modifier = Modifier.graphicsLayer {
                        shape = RoundedCornerShape(windowCorner.value)
                        clip = true
                    },
                )
            }

            // Страница аудиокниги: тот же push, что у деталей аниме (сдвиг справа, «назад» — вправо).
            composable<BookDetailsRoute>(
                enterTransition = {
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = MotionTokens.sheetOffset)
                },
                popExitTransition = {
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = MotionTokens.tweenStandard())
                },
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<BookDetailsRoute>()
                val bookLanguage by homeViewModel.uiLanguage.collectAsStateWithLifecycle()
                val windowCorner = transition.animateDp(label = "bookWindowCorner") { state ->
                    if (state == EnterExitState.Visible) 0.dp else 42.dp
                }
                BookDetailsScreen(
                    source = route.source,
                    key = route.key,
                    title = route.title,
                    cover = route.cover,
                    language = bookLanguage,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { b -> navController.navigateToBook(b.ref.source.value, b.ref.key, b.title, b.coverUrl) },
                    modifier = Modifier.graphicsLayer {
                        shape = RoundedCornerShape(windowCorner.value)
                        clip = true
                    },
                )
            }

            composable<AddEditRoute>(
                enterTransition = { if (workspaceModal(targetState)) workspaceModalEnter() else null },
                popExitTransition = { if (workspaceModal(initialState)) workspaceModalExit() else null },
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<AddEditRoute>()
                AddEditScreen(
                    navController = navController,
                    viewModel = koinViewModel<AddEditViewModel>(viewModelStoreOwner = activityViewModelOwner),
                    animeId = route.animeId,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(
                    navController = navController,
                    viewModel = settingsViewModel,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    // Маршрут — значит есть куда возвращаться, и кнопка «назад» в шапке нужна.
                    // На странице рабочей области onBack не передают, и кнопки нет (TICKET-07).
                    onBack = { navController.popBackStack() },
                )
            }

            composable<InspectRoute>(
                enterTransition = { if (workspaceModal(targetState)) workspaceModalEnter() else null },
                popExitTransition = { if (workspaceModal(initialState)) workspaceModalExit() else null },
            ) {
                InspectScreen(
                    navController = navController,
                    viewModel = koinViewModel<InspectViewModel>(viewModelStoreOwner = activityViewModelOwner),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    onBack = { navController.popBackStack() },
                )
            }

        }
        val audiobookLanguage by homeViewModel.uiLanguage.collectAsStateWithLifecycle()
        AudiobookPlayerHost(audiobookLanguage)

        if (startupSplash.visible) {
            // Главную открываем, когда коллекция уже в списке, а не пустой кадр перед ней.
            val homeListLoaded = remember(homeViewModel) {
                homeViewModel.uiState.map { it.isListLoaded }.distinctUntilChanged()
            }.collectAsState(initial = false)
            StartupSplashOverlay(
                state = startupSplash,
                contentReady = {
                    startupSplash.nextRoute != StartupSplashState.HOME || homeListLoaded.value
                },
            )
        }
        }
    }
}
