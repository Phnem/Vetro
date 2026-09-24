package com.example.myapplication

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.LocalModernUi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.phnem.vetro.R
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.models.SortOption
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.home.WorkspaceSortNotificationActions
import com.example.myapplication.ui.navigation.navigateToAddEdit
import com.example.myapplication.ui.shared.GlassPreset
import com.example.myapplication.ui.shared.adaptiveGlassBackdrop
import com.example.myapplication.ui.shared.rememberAdaptiveGlassEffects
import com.example.myapplication.ui.shared.components.GenreFilterPillSelection
import com.example.myapplication.ui.shared.components.GlassIconButton
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandBlue
import com.example.myapplication.ui.shared.theme.DarkBackground
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.iosRowHighlight
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import com.example.myapplication.ui.shared.theme.softPlateShadowForLightSheet
import com.example.myapplication.ui.shared.inertialCollision
import com.example.myapplication.ui.shared.rememberInertialCollisionState
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop

@Composable
fun isAppInDarkTheme(): Boolean {
    return MaterialTheme.colorScheme.background.toArgb() == DarkBackground.toArgb()
}

// ==========================================
// Simple glass card ("simp glass" style)
// ==========================================
@Composable
fun SimpGlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = isAppInDarkTheme()
    val glassEffects = rememberAdaptiveGlassEffects(GlassPreset.Card)
    val shineColor = if (isDark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)
    val borderStroke = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)

    Box(
        modifier = modifier
            .clip(shape)
            .adaptiveGlassBackdrop(backdrop = backdrop, shape = shape, effects = glassEffects)
            .border(0.5.dp, borderStroke, shape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path()
            when (outline) {
                is Outline.Rounded -> path.addRoundRect(outline.roundRect)
                is Outline.Generic -> path.addPath(outline.path)
                is Outline.Rectangle -> path.addRect(outline.rect)
            }
            drawPath(
                path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        shineColor,
                        Color.Transparent,
                        Color.Transparent,
                        shineColor.copy(alpha = 0.1f)
                    )
                ),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        content()
    }
}

// ==========================================
// GlassActionDock
// ==========================================
@Composable
fun GlassActionDock(
    backdrop: Backdrop,
    isFloating: Boolean,
    strings: UiStrings,
    filterSelectedTags: List<String>,
    updates: List<AnimeUpdate>,
    onOpenSort: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMediaTypeFilter: () -> Unit,
    modifier: Modifier = Modifier,
    middleAction: com.example.myapplication.ui.home.TopDockMiddleAction =
        com.example.myapplication.ui.home.TopDockMiddleAction.SYNC_PANEL,
    onOpenStats: () -> Unit = {},
    /** См. одноимённый параметр [WorkspaceSortNotificationActions] — док только прокидывает его. */
    showMiddleAction: Boolean = true,
) {
    val isDark = isAppInDarkTheme()
    val glassEffects = rememberAdaptiveGlassEffects(GlassPreset.Card)
    // Тот же тумблер, что у нижнего дока: два дока одного экрана из разных материалов выглядели
    // бы как недоделка. Ветка структурная, и переключение тумблера на живом экране может один
    // раз показать плоскую заливку — для dev-флага это приемлемо, восстановление штатное
    // (см. GlassBackdropRecovery).
    val frosted = LocalModernUi.current
    val frostedMaterial = FrostedMaterials.dock()
    val dockShape = RoundedCornerShape(32.dp)
    val topPadding by animateDpAsState(
        targetValue = if (isFloating) 16.dp else 0.dp,
        animationSpec = MotionTokens.barRevealDp,
        label = "dockPadding"
    )
    val borderStrokeBase = if (isDark) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)
    val borderColor by animateColorAsState(
        targetValue = if (isFloating) borderStrokeBase else Color.Transparent,
        label = "border"
    )
    val shineColorBase = if (isDark) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.6f)
    val shineAlpha by animateFloatAsState(
        targetValue = if (isFloating) 1f else 0f,
        label = "shineAlpha"
    )
    val buttonBgColor by animateColorAsState(
        targetValue = if (isFloating) Color.Transparent else
            if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else Color.Black.copy(alpha = 0.04f),
        label = "btnBg"
    )

    AnimatedVisibility(
        visible = isFloating,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = MotionTokens.barReveal()
        ) + fadeIn(animationSpec = MotionTokens.barReveal()),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = MotionTokens.standard()
        ) + fadeOut(animationSpec = MotionTokens.standard()),
        modifier = modifier
            .padding(top = topPadding)
            .statusBarsPadding()
    ) {
        Box {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(dockShape)
                    .then(
                        if (frosted) {
                            Modifier.frostedGlass(
                                backdrop = backdrop,
                                shape = dockShape,
                                material = frostedMaterial,
                            )
                        } else {
                            Modifier
                                .adaptiveGlassBackdrop(
                                    backdrop = backdrop,
                                    shape = dockShape,
                                    effects = glassEffects,
                                )
                                // Кант матового материала рисует он сам — второй поверх него
                                // дал бы двойную линию по тому же контуру.
                                .border(0.5.dp, borderColor, dockShape)
                        },
                    )
            ) {
                if (shineAlpha > 0f) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val rect = Rect(offset = Offset.Zero, size = size)
                        val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(32.dp.toPx()))) }
                        drawPath(
                            path,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    shineColorBase.copy(alpha = shineColorBase.alpha * shineAlpha),
                                    Color.Transparent,
                                    Color.Transparent,
                                    shineColorBase.copy(alpha = 0.05f * shineAlpha)
                                )
                            ),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            }

            WorkspaceSortNotificationActions(
                strings = strings,
                filterSelectedTags = filterSelectedTags,
                updatesCount = updates.size,
                onOpenSort = onOpenSort,
                onOpenNotifications = onOpenNotifications,
                onOpenMediaTypeFilter = onOpenMediaTypeFilter,
                dockButtonBackground = buttonBgColor,
                useDockSizing = true,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                middleAction = middleAction,
                onOpenStats = onOpenStats,
                showMiddleAction = showMiddleAction,
            )
        }
    }
}

// ==========================================
// ПАНЕЛЬ НАВИГАЦИИ (Compact Style)
// ==========================================
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun GlassBottomNavigation(
    backdrop: Backdrop,
    nav: androidx.navigation.NavController,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    currentLanguage: AppLanguage,
    onShowStats: () -> Unit,
    onShowNotifs: () -> Unit,
    onInspectClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isSearchActive: Boolean,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val isDark = isAppInDarkTheme()

    val glassEffects = rememberAdaptiveGlassEffects(GlassPreset.CompactNav)
    // Более «пухлая» капсула (референс — Telegram): выше и с полным пилюльным скруглением.
    val navHeight = 74.dp
    val navShape = RoundedCornerShape(navHeight / 2)
    val currentThemeColor = MaterialTheme.colorScheme.onSurface
    // Иконки дока — ярче/белее: чистый белый на полной непрозрачности в тёмной теме.
    val dockIconTint = if (isDark) Color.White else currentThemeColor
    val borderStroke = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)

    val ru = currentLanguage == AppLanguage.RU
    val labelInspect = if (ru) "Кадр" else "Frame"
    val labelStats = if (ru) "Статистика" else "Stats"
    val labelAdd = if (ru) "Добавить" else "Add"
    val labelSettings = if (ru) "Настройки" else "Settings"

    Box(
        modifier = modifier
            .padding(bottom = 24.dp)
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Капсула на всю строку: ~80% ширины экрана, элементы равномерно распределены.
                .fillMaxWidth(0.80f)
                .height(navHeight)
                .clip(navShape)
                .adaptiveGlassBackdrop(backdrop = backdrop, shape = navShape, effects = glassEffects)
                .border(0.5.dp, borderStroke, navShape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                // Иконка+подпись — единый блок, центрируем его по вертикали в капсуле.
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // --- Кадр (inspect) ---
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .fluidClickable {
                            performHaptic(view, "light")
                            onInspectClick()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    with(sharedTransitionScope) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = "inspect_container"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                                )
                                .background(Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frame_inspect_24),
                                contentDescription = "Scene search",
                                tint = dockIconTint,
                                modifier = Modifier
                                    .size(34.dp)
                                    .sharedElement(
                                        rememberSharedContentState(key = "inspect_icon"),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                            )
                        }
                    }
                    DockLabel(labelInspect, currentThemeColor)
                }

                // --- Статистика ---
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            performHaptic(view, "light")
                            onShowStats()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HeroiconsSquaresPlus,
                            contentDescription = "Stats",
                            tint = dockIconTint,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    DockLabel(labelStats, currentThemeColor)
                }

                // --- Добавить ---
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            performHaptic(view, "success")
                            nav.navigateToAddEdit()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    with(sharedTransitionScope) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = "fab_container"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                    clipInOverlayDuringTransition = OverlayClip(CircleShape)
                                )
                                .background(Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = HeroiconsPlus,
                                contentDescription = "Add",
                                tint = dockIconTint,
                                modifier = Modifier
                                    .size(36.dp)
                                    .sharedElement(
                                        rememberSharedContentState(key = "fab_icon"),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                            )
                        }
                    }
                    DockLabel(labelAdd, currentThemeColor)
                }

                // --- Настройки ---
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .fluidClickable {
                            performHaptic(view, "light")
                            onSettingsClick()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    with(sharedTransitionScope) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = "settings_container"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    // ScaleToBounds: контент меряется один раз и масштабируется
                                    // при переходе (draw-time), без пер-кадрового relayout всего
                                    // списка настроек из 48dp-иконки — снимает провал FPS (§ perf).
                                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                                )
                                .background(Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = dockIconTint,
                                modifier = Modifier
                                    .size(34.dp)
                                    .sharedElement(
                                        rememberSharedContentState(key = "settings_icon"),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                            )
                        }
                    }
                    DockLabel(labelSettings, currentThemeColor)
                }
            }
        }

        GlassIconButton(
            icon = Icons.Default.Search,
            onClick = {
                performHaptic(view, "light")
                onSearchClick()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Приподнят над доком (референс — Telegram): отступ снизу = высота дока + зазор.
                .padding(end = 24.dp, bottom = navHeight + 12.dp),
            size = 52.dp,
            iconSize = 26.dp,
            backdrop = backdrop,
            backgroundColor = Color.Transparent,
            contentDescription = "Search",
            tint = if (isSearchActive) BrandBlue else dockIconTint
        )
    }
}

/**
 * Габариты иконки капсульного дока.
 *
 * [DockSharedBoundsSize] — узел shared-element перехода; он же задаёт, из какого прямоугольника
 * иконка вырастает в окно, поэтому совпадает с прежним доком: геометрия перехода не должна
 * зависеть от того, какой тумблер включён.
 */
private val DockSharedBoundsSize = 36.dp
private val DockIconSize = 26.dp

/** Подпись под иконкой дока (референс — Telegram). Компактная, приглушённая.
 *  8.5sp + колонка 60dp: длинные русские подписи («Статистика», «Настройки»)
 *  влезают целиком и не обрезаются клипом колонки. */
@Composable
private fun DockLabel(text: String, color: Color) {
    Text(
        text = text,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 8.5.sp,
        color = color.copy(alpha = 0.75f),
        maxLines = 1,
        modifier = Modifier.padding(top = 1.dp),
    )
}

// ==========================================
// Built-in vector icons (Heroicons-style paths)
// ==========================================

private var heroiconsPlusCached: ImageVector? = null
val HeroiconsPlus: ImageVector
    get() {
        if (heroiconsPlusCached != null) return heroiconsPlusCached!!
        heroiconsPlusCached = ImageVector.Builder(
            name = "plus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10.75f, 4.75f)
                arcToRelative(0.75f, 0.75f, 0f, false, false, -1.5f, 0f)
                verticalLineToRelative(4.5f)
                horizontalLineToRelative(-4.5f)
                arcToRelative(0.75f, 0.75f, 0f, false, false, 0f, 1.5f)
                horizontalLineToRelative(4.5f)
                verticalLineToRelative(4.5f)
                arcToRelative(0.75f, 0.75f, 0f, false, false, 1.5f, 0f)
                verticalLineToRelative(-4.5f)
                horizontalLineToRelative(4.5f)
                arcToRelative(0.75f, 0.75f, 0f, false, false, 0f, -1.5f)
                horizontalLineToRelative(-4.5f)
                verticalLineToRelative(-4.5f)
                close()
            }
        }.build()
        return heroiconsPlusCached!!
    }

private var heroiconsSquaresPlusCached: ImageVector? = null
val HeroiconsSquaresPlus: ImageVector
    get() {
        if (heroiconsSquaresPlusCached != null) return heroiconsSquaresPlusCached!!
        heroiconsSquaresPlusCached = ImageVector.Builder(
            name = "squares-plus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(13.5f, 16.875f)
                horizontalLineToRelative(3.375f)
                moveToRelative(0f, 0f)
                horizontalLineToRelative(3.375f)
                moveToRelative(-3.375f, 0f)
                verticalLineTo(13.5f)
                moveToRelative(0f, 3.375f)
                verticalLineToRelative(3.375f)
                moveTo(6f, 10.5f)
                horizontalLineToRelative(2.25f)
                arcToRelative(2.25f, 2.25f, 0f, false, false, 2.25f, -2.25f)
                verticalLineTo(6f)
                arcToRelative(2.25f, 2.25f, 0f, false, false, -2.25f, -2.25f)
                horizontalLineTo(6f)
                arcTo(2.25f, 2.25f, 0f, false, false, 3.75f, 6f)
                verticalLineToRelative(2.25f)
                arcTo(2.25f, 2.25f, 0f, false, false, 6f, 10.5f)
                close()
                moveToRelative(0f, 9.75f)
                horizontalLineToRelative(2.25f)
                arcTo(2.25f, 2.25f, 0f, false, false, 10.5f, 18f)
                verticalLineToRelative(-2.25f)
                arcToRelative(2.25f, 2.25f, 0f, false, false, -2.25f, -2.25f)
                horizontalLineTo(6f)
                arcToRelative(2.25f, 2.25f, 0f, false, false, -2.25f, 2.25f)
                verticalLineTo(18f)
                arcTo(2.25f, 2.25f, 0f, false, false, 6f, 20.25f)
                close()
                moveToRelative(9.75f, -9.75f)
                horizontalLineTo(18f)
                arcToRelative(2.25f, 2.25f, 0f, false, false, 2.25f, -2.25f)
                verticalLineTo(6f)
                arcTo(2.25f, 2.25f, 0f, false, false, 18f, 3.75f)
                horizontalLineToRelative(-2.25f)
                arcTo(2.25f, 2.25f, 0f, false, false, 13.5f, 6f)
                verticalLineToRelative(2.25f)
                arcToRelative(2.25f, 2.25f, 0f, false, false, 2.25f, 2.25f)
                close()
            }
        }.build()
        return heroiconsSquaresPlusCached!!
    }

private var heroiconsRectangleStackCached: ImageVector? = null
val HeroiconsRectangleStack: ImageVector
    get() {
        if (heroiconsRectangleStackCached != null) return heroiconsRectangleStackCached!!
        heroiconsRectangleStackCached = ImageVector.Builder(
            name = "rectangle-stack",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6f, 6.878f)
                verticalLineTo(6f)
                arcToRelative(2.25f, 2.25f, 0f, false, true, 2.25f, -2.25f)
                horizontalLineToRelative(7.5f)
                arcTo(2.25f, 2.25f, 0f, false, true, 18f, 6f)
                verticalLineToRelative(0.878f)
                moveToRelative(-12f, 0f)
                curveToRelative(0.235f, -0.083f, 0.487f, -0.128f, 0.75f, -0.128f)
                horizontalLineToRelative(10.5f)
                curveToRelative(0.263f, 0f, 0.515f, 0.045f, 0.75f, 0.128f)
                moveToRelative(-12f, 0f)
                arcTo(2.25f, 2.25f, 0f, false, false, 4.5f, 9f)
                verticalLineToRelative(0.878f)
                moveToRelative(13.5f, -3f)
                arcTo(2.25f, 2.25f, 0f, false, true, 19.5f, 9f)
                verticalLineToRelative(0.878f)
                moveToRelative(0f, 0f)
                arcToRelative(2.246f, 2.246f, 0f, false, false, -0.75f, -0.128f)
                horizontalLineTo(5.25f)
                curveToRelative(-0.263f, 0f, -0.515f, 0.045f, -0.75f, 0.128f)
                moveToRelative(15f, 0f)
                arcTo(2.25f, 2.25f, 0f, false, true, 21f, 12f)
                verticalLineToRelative(6f)
                arcToRelative(2.25f, 2.25f, 0f, false, true, -2.25f, 2.25f)
                horizontalLineTo(5.25f)
                arcTo(2.25f, 2.25f, 0f, false, true, 3f, 18f)
                verticalLineToRelative(-6f)
                curveToRelative(0f, -0.98f, 0.626f, -1.813f, 1.5f, -2.122f)
            }
        }.build()
        return heroiconsRectangleStackCached!!
    }

// ==========================================
// ЦВЕТА И РАСШИРЕНИЯ ДЛЯ СТАРОГО ДИЗАЙНА
// ==========================================
private val IconFilterColor = Color(0xFFE85002)

private sealed interface SortGridSelection {
    data class Sort(val option: SortOption, val isAscending: Boolean) : SortGridSelection
    data object Genres : SortGridSelection
}

fun SortOption.getIcon(): ImageVector = when (this) {
    SortOption.RATING -> Icons.Rounded.Star
    SortOption.EPISODES -> Icons.Outlined.Tv
    SortOption.TITLE -> Icons.AutoMirrored.Filled.Sort
}

fun SortOption.getAccentColor(): Color = when (this) {
    SortOption.RATING -> OverlayThemeTokens.AccentOrange
    SortOption.EPISODES -> OverlayThemeTokens.AccentBlue
    SortOption.TITLE -> OverlayThemeTokens.AccentYellow
}

// ==========================================
// SORT & FILTER OVERLAYS
// ==========================================

@Composable
fun SortFilterOverlay(
    visibleState: MutableTransitionState<Boolean>,
    strings: UiStrings,
    sortOption: SortOption,
    sortAscending: Boolean = false,
    filterSelectedTags: List<String>,
    onDismiss: () -> Unit,
    onApplySort: (SortOption, Boolean) -> Unit,
    onApplyOpenGenreFilter: () -> Unit
) {
    val view = LocalView.current
    val isDark = isAppInDarkTheme()
    val panelBg = if (isDark) DarkBackground else MaterialTheme.colorScheme.surface
    val rim = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val onCard = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val cardBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val isOpening = visibleState.targetState
    var draftSelection by remember(sortOption, sortAscending, isOpening) {
        mutableStateOf<SortGridSelection>(SortGridSelection.Sort(sortOption, sortAscending))
    }

    val swipe = rememberIosSheetSwipe { onDismiss() }
    // Один источник правды с [SortOption.getAccentColor] — раньше палитра была продублирована
    // здесь литералами, и правка в одном месте не доезжала до второго.
    val accentRating = OverlayThemeTokens.AccentOrange
    val accentEpisodes = OverlayThemeTokens.AccentBlue
    val accentTitle = OverlayThemeTokens.AccentYellow
    val accentGenres = OverlayThemeTokens.AccentPurple
    fun sortAccent(o: SortOption) = o.getAccentColor()
    val applyAccent = when (val d = draftSelection) {
        SortGridSelection.Genres -> accentGenres
        is SortGridSelection.Sort -> sortAccent(d.option)
    }

    BackHandler { onDismiss() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Шторка выезжает снизу и уходит вниз; физика и хореография — в MotionBottomSheet.
        MotionBottomSheet(
            visibleState = visibleState,
            onDismiss = onDismiss,
            isDark = isDark,
            swipe = swipe,
            panelModifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                GrabberHandle(isDark, modifier = swipe.handleModifier)
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = strings.sortSheetTitle,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        letterSpacing = (-0.3).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = strings.sortSheetSubtitle,
                        fontFamily = SnProFamily,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                    )

                    val curSort = draftSelection as? SortGridSelection.Sort
                    fun toggleSort(o: SortOption) {
                        performHaptic(view, "light")
                        val cur = draftSelection
                        draftSelection = if (cur is SortGridSelection.Sort && cur.option == o) {
                            SortGridSelection.Sort(o, !cur.isAscending)
                        } else {
                            SortGridSelection.Sort(o, isAscending = false)
                        }
                    }
                    fun orderLabel(asc: Boolean) =
                        if (asc) strings.sortOrderAscending else strings.sortOrderDescending

                    SortOptionRow(
                        icon = SortOption.RATING.getIcon(),
                        accent = accentRating,
                        title = strings.ratingHigh,
                        subtitle = if (curSort?.option == SortOption.RATING) orderLabel(curSort.isAscending) else strings.sortSubtitleRating,
                        selected = curSort?.option == SortOption.RATING,
                        isDark = isDark,
                        onClick = { toggleSort(SortOption.RATING) }
                    )
                    SortOptionRow(
                        icon = SortOption.EPISODES.getIcon(),
                        accent = accentEpisodes,
                        title = strings.sortTileEpisodesTitle,
                        subtitle = if (curSort?.option == SortOption.EPISODES) orderLabel(curSort.isAscending) else strings.sortSubtitleEpisodes,
                        selected = curSort?.option == SortOption.EPISODES,
                        isDark = isDark,
                        onClick = { toggleSort(SortOption.EPISODES) }
                    )
                    SortOptionRow(
                        icon = SortOption.TITLE.getIcon(),
                        accent = accentTitle,
                        title = strings.titleAZ,
                        subtitle = if (curSort?.option == SortOption.TITLE) orderLabel(curSort.isAscending) else strings.sortSubtitleTitle,
                        selected = curSort?.option == SortOption.TITLE,
                        isDark = isDark,
                        onClick = { toggleSort(SortOption.TITLE) }
                    )
                    SortOptionRow(
                        icon = Icons.Outlined.FilterList,
                        accent = accentGenres,
                        title = strings.filterByGenre,
                        subtitle = strings.sortSubtitleGenres,
                        selected = draftSelection == SortGridSelection.Genres,
                        isDark = isDark,
                        onClick = {
                            performHaptic(view, "light")
                            draftSelection = SortGridSelection.Genres
                        }
                    )

                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(SquircleShape(27.dp))
                            .background(applyAccent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                when (val d = draftSelection) {
                                    SortGridSelection.Genres -> { onApplyOpenGenreFilter(); onDismiss() }
                                    is SortGridSelection.Sort -> {
                                        if (d.option == sortOption && d.isAscending == sortAscending) onDismiss()
                                        else { onApplySort(d.option, d.isAscending); onDismiss() }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = strings.sortApply,
                            // По яркости заливки, а не сравнением с одним конкретным акцентом:
                            // акценты теперь все тёплые и светлые, и белый текст на янтарном
                            // читался бы плохо. Порог подобран так, что брендовый #E85002
                            // по-прежнему получает чёрный текст — как было до правки.
                            color = if (applyAccent.luminance() > 0.2f) {
                                Color.Black.copy(alpha = 0.85f)
                            } else {
                                Color.White
                            },
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** Карточка-опция сортировки (iOS «Set Status» стиль): icon-well + заголовок/подпись + радио. */
@Composable
private fun SortOptionRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val shape = SquircleShape(18.dp)
    val cardBg = if (selected) accent.copy(alpha = if (isDark) 0.20f else 0.13f) else IosDesign.elevatedCard(isDark)
    val wellBg = if (isDark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.75f)
    val titleColor = if (selected) accent else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(cardBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = iosRowHighlight(isDark, shape),
                onClick = onClick
            )
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(SquircleShape(12.dp))
                .background(wellBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(accent)
                    else Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/**
 * Кнопка применения для SortFilterOverlay. Вынесена из тела [SortFilterOverlay],
 * чтобы декомпозировать большой composable и не превращать glass.kt в God Object.
 */
@Composable
private fun SortApplyButton(
    isDark: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(OverlayThemeTokens.ApplyButtonCornerRadius),
        colors = if (isDark) {
            ButtonDefaults.buttonColors(
                containerColor = OverlayThemeTokens.ApplyButtonContainerDark,
                contentColor = OverlayThemeTokens.ApplyButtonLabelSoft
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
        },
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp
        )
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun SortSortTile(
    option: SortOption,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    selection: SortGridSelection,
    strings: UiStrings,
    isDark: Boolean,
    rim: Color,
    cardBg: Color,
    muted: Color,
    modifier: Modifier = Modifier,
    onSelect: (SortGridSelection) -> Unit
) {
    val isActive = selection is SortGridSelection.Sort && selection.option == option
    val isAscending =
        if (selection is SortGridSelection.Sort) selection.isAscending else true

    val scheme = MaterialTheme.colorScheme
    val mutedIconTint = scheme.onSurfaceVariant.copy(alpha = 0.42f)
    val targetIconTint = if (isActive) accentColor else mutedIconTint
    val animatedIconTint by animateColorAsState(
        targetValue = targetIconTint,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sortIconTint"
    )
    val targetBorderColor = if (isActive) accentColor else rim
    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sortTileBorder"
    )

    val shape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius)
    val tileBg = if (isDark) OverlayThemeTokens.TileBackgroundDark else cardBg
    val glowAlpha = if (isActive) {
        if (isDark) 0.22f else OverlayThemeTokens.TileGlowAlphaLight
    } else 0f
    val accentGlow = accentColor.copy(alpha = glowAlpha)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = OverlayThemeTokens.SortTileMinHeight)
            .then(
                if (isDark) Modifier else Modifier.softPlateShadowForLightSheet(
                    isDark = false,
                    shape = shape,
                    elevation = OverlayThemeTokens.SortOverlayGridLightShadowElevation,
                )
            )
            .clip(shape)
            .background(tileBg)
            .background(
                Brush.radialGradient(
                    colors = listOf(accentGlow, Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 320f
                )
            )
            .glassFill(isDark)
            .glassEdge(OverlayThemeTokens.TileCornerRadius, isDark)
            .border(1.dp, animatedBorderColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onSelect(
                    if (isActive) {
                        SortGridSelection.Sort(option, !isAscending)
                    } else {
                        SortGridSelection.Sort(option, isAscending = false)
                    }
                )
            }
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(OverlayThemeTokens.IconBoxCorner))
                    .background(
                        if (isDark) OverlayThemeTokens.TileIconBgDark
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = animatedIconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) {
                        if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                    } else {
                        muted
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (isActive) {
                    Text(
                        text = if (isAscending) strings.sortOrderAscending else strings.sortOrderDescending,
                        style = OverlayThemeTokens.MetricLabel,
                        color = if (isDark) {
                            Color.White.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = subtitle,
                        style = OverlayThemeTokens.MetricLabel,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreSortTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selection: SortGridSelection,
    isDark: Boolean,
    rim: Color,
    cardBg: Color,
    muted: Color,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    onSelect: (SortGridSelection) -> Unit
) {
    val isActive = selection == SortGridSelection.Genres
    val genreAccent = if (isDark) IconFilterColor else MaterialTheme.colorScheme.primary

    val scheme = MaterialTheme.colorScheme
    val mutedIconTint = scheme.onSurfaceVariant.copy(alpha = 0.42f)
    val targetIconTint = if (isActive) genreAccent else mutedIconTint
    val animatedIconTint by animateColorAsState(
        targetValue = targetIconTint,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "genreIconTint"
    )
    val targetBorderColor = if (isActive) genreAccent else rim
    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "genreSortTileBorder"
    )

    val shape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius)
    val tileBg = if (isDark) OverlayThemeTokens.TileBackgroundDark else cardBg
    val glowAlpha = if (isActive || badgeCount > 0) {
        if (isDark) 0.22f else OverlayThemeTokens.TileGlowAlphaLight
    } else 0f
    val accentGlow = genreAccent.copy(alpha = glowAlpha)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = OverlayThemeTokens.SortTileMinHeight)
            .then(
                if (isDark) Modifier else Modifier.softPlateShadowForLightSheet(
                    isDark = false,
                    shape = shape,
                    elevation = OverlayThemeTokens.SortOverlayGridLightShadowElevation,
                )
            )
            .clip(shape)
            .background(tileBg)
            .background(
                Brush.radialGradient(
                    colors = listOf(accentGlow, Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 320f
                )
            )
            .glassFill(isDark)
            .glassEdge(OverlayThemeTokens.TileCornerRadius, isDark)
            .border(1.dp, animatedBorderColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onSelect(SortGridSelection.Genres) }
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(OverlayThemeTokens.IconBoxCorner))
                    .background(
                        if (isDark) OverlayThemeTokens.TileIconBgDark
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = animatedIconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) {
                        if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                    } else {
                        muted
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = OverlayThemeTokens.MetricLabel,
                    color = muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (badgeCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(genreAccent.copy(alpha = 0.35f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GenreFilterOverlay(
    visibleState: MutableTransitionState<Boolean>,
    strings: UiStrings,
    filterSelectedTags: List<String>,
    filterCategoryType: String,
    currentLanguage: AppLanguage,
    onTagToggle: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isAppInDarkTheme()
    val panelBg = if (isDark) DarkBackground else MaterialTheme.colorScheme.surface
    val swipe = rememberIosSheetSwipe(onDismiss)

    val collisionState = rememberInertialCollisionState()
    LaunchedEffect(visibleState.targetState) {
        if (visibleState.targetState) {
            collisionState.triggerCollision(impactForce = 35f, stiffness = 200f, dampingRatio = 0.5f)
        }
    }

    BackHandler { onDismiss() }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Bottom sheet «фильтр по жанрам»: выезжает снизу и уходит вниз (см. MotionBottomSheet).
        MotionBottomSheet(
            visibleState = visibleState,
            onDismiss = onDismiss,
            isDark = isDark,
            swipe = swipe,
            panelModifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                GrabberHandle(isDark, modifier = swipe.handleModifier)
                Column(
                    modifier = Modifier
                        .padding(start = 20.dp, top = 2.dp, end = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inertialCollision(state = collisionState, index = 0, baseMultiplier = 2.5f)
                    ) {
                        Text(
                            text = strings.filterByGenre,
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inertialCollision(state = collisionState, index = 1, baseMultiplier = 2.5f)
                    ) {
                        GenreFilterPillSelection(
                            strings = strings,
                            currentLanguage = currentLanguage,
                            selectedTags = filterSelectedTags,
                            activeCategory = filterCategoryType,
                            onTagToggle = onTagToggle
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .inertialCollision(state = collisionState, index = 2, baseMultiplier = 2.5f)
                            .clip(SquircleShape(26.dp))
                            .background(BrandBlue)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = strings.genreFilterDone,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SnProFamily,
                            fontSize = 17.sp
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}