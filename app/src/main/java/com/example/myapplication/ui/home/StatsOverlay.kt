package com.example.myapplication.ui.home

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.domain.stats.StatsCardExplanationState
import com.example.myapplication.domain.stats.StatsCardKind
import com.example.myapplication.domain.stats.StatsExplanationCoordinator
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.home.stats.StatsCardDeckContent
import com.example.myapplication.ui.home.stats.StatsCardDetailContent
import com.example.myapplication.ui.home.stats.StatsInsightsSection
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.example.myapplication.ui.shared.inertialCollision
import com.example.myapplication.ui.shared.rememberInertialCollisionState
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.iosSheetContainer
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.DarkBackground
import com.example.myapplication.ui.shared.theme.OverlayGlassPanel
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import com.example.myapplication.ui.shared.theme.softPlateShadowForLightSheet
import org.koin.compose.koinInject
import java.util.Locale

// ==========================================
// StatsOverlay — стеклянный попап статистики (Спринт 2 редизайна)
// ==========================================

@Composable
fun StatsOverlay(
    animeList: List<Anime>,
    strings: UiStrings,
    appLanguage: AppLanguage,
    footerPhrase: String,
    onDismiss: () -> Unit
) {
    val isDark = isAppInDarkTheme()
    var visible by remember { mutableStateOf(false) }
    // null — режим «колода», иначе — детальный режим тапнутой карточки
    var expandedCard by remember { mutableStateOf<StatsCardKind?>(null) }
    val explanationCoordinator: StatsExplanationCoordinator = koinInject()
    val explanations by explanationCoordinator.state.collectAsState()

    // Состояние шторки — отдельным объектом, чтобы снимать оверлей ровно тогда, когда она
    // действительно схлопнулась, а не по таймеру.
    val panelState = remember { MutableTransitionState(false) }
    panelState.targetState = visible

    LaunchedEffect(Unit) { visible = true }

    fun triggerDismiss() {
        visible = false
    }

    LaunchedEffect(visible) {
        if (!visible) {
            // Фиксированная задержка обрывала закрытие на середине: панель пропадала вместо
            // того, чтобы схлопнуться.
            snapshotFlow { panelState.isIdle && !panelState.currentState }.first { it }
            onDismiss()
        }
    }

    BackHandler {
        if (expandedCard != null) expandedCard = null else triggerDismiss()
    }

    val collisionState = rememberInertialCollisionState()
    LaunchedEffect(visible) {
        if (visible) {
            collisionState.triggerCollision(impactForce = 55f, stiffness = 180f, dampingRatio = 0.5f)
        }
    }

    val swipe = rememberIosSheetSwipe { triggerDismiss() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Шторка выезжает снизу и уходит вниз (см. MotionBottomSheet). По высоте — по контенту
        // (когда жанров нет — короткая, без пустоты снизу), но не выше 92% экрана; StatsContent
        // внутри скроллится, если контент высокий.
        MotionBottomSheet(
            visibleState = panelState,
            onDismiss = ::triggerDismiss,
            isDark = isDark,
            swipe = swipe,
            panelModifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f),
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                GrabberHandle(isDark, modifier = swipe.handleModifier)
                StatsContent(
                    animeList = animeList,
                    strings = strings,
                    appLanguage = appLanguage,
                    isDark = isDark,
                    footerPhrase = footerPhrase,
                    collisionState = collisionState,
                    expandedCard = expandedCard,
                    explanations = explanations,
                    onCardTap = { expandedCard = it },
                    onBackFromDetail = { expandedCard = null },
                    onDismiss = ::triggerDismiss
                )
            }
        }
    }
}

/**
 * Декомпозированный «контент» — два режима под AnimatedContent:
 * колода из трёх карточек (тап — детали) и детальный режим с AI-объяснением.
 */
@Composable
private fun StatsContent(
    animeList: List<Anime>,
    strings: UiStrings,
    appLanguage: AppLanguage,
    isDark: Boolean,
    footerPhrase: String,
    collisionState: com.example.myapplication.ui.shared.InertialCollisionState,
    expandedCard: StatsCardKind?,
    explanations: Map<StatsCardKind, StatsCardExplanationState>,
    onCardTap: (StatsCardKind) -> Unit,
    onBackFromDetail: () -> Unit,
    onDismiss: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll, flingBehavior = IosScroll.flingBehavior())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Morph «карточка → детали»: фоновые карточки уходят (fade+scale-out),
        // детальный экран влетает тем же sheetPresent-спрингом, что и сама шторка.
        AnimatedContent(
            targetState = expandedCard,
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 80)) + slideInVertically(
                    initialOffsetY = { it / 8 },
                    animationSpec = MotionTokens.sheetPresent()
                )).togetherWith(
                    fadeOut(tween(150)) + scaleOut(targetScale = 0.94f, animationSpec = tween(180))
                )
            },
            label = "statsMode",
            modifier = Modifier.fillMaxWidth()
        ) { card ->
            if (card == null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inertialCollision(state = collisionState, index = 0, baseMultiplier = 3f)
                    ) {
                        Text(
                            text = strings.statsTitle,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = SnProFamily,
                                fontSize = 24.sp,
                                letterSpacing = (-0.3).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inertialCollision(state = collisionState, index = 1, baseMultiplier = 3f)
                    ) {
                        Text(
                            text = strings.statsSubtitle,
                            style = OverlayThemeTokens.SectionLabel,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = OverlayThemeTokens.LabelMutedAlpha)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inertialCollision(state = collisionState, index = 2, baseMultiplier = 3f)
                    ) {
                        StatsCardDeckContent(
                            animeList = animeList,
                            strings = strings,
                            appLanguage = appLanguage,
                            isDark = isDark,
                            onCardTap = onCardTap
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    StatsInsightsSection(
                        animeList = animeList,
                        appLanguage = appLanguage,
                        isDark = isDark,
                    )

                    Spacer(Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inertialCollision(state = collisionState, index = 3, baseMultiplier = 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        StatsDoneButton(
                            label = strings.statsOk,
                            isDark = isDark,
                            onClick = onDismiss
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StatsCardDetailContent(
                        kind = card,
                        animeList = animeList,
                        strings = strings,
                        appLanguage = appLanguage,
                        isDark = isDark,
                        explanationState = explanations[card] ?: StatsCardExplanationState.Loading,
                        footerPhrase = footerPhrase,
                        onBack = onBackFromDetail
                    )

                    Spacer(Modifier.height(24.dp))

                    StatsDoneButton(
                        label = strings.statsOk,
                        isDark = isDark,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

/** Готовая «глянцевая» CTA-кнопка статистики: pill + edge-highlight + accent. */
@Composable
private fun StatsDoneButton(
    label: String,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isDark) {
        OverlayThemeTokens.AccentNeonBlue
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isDark) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .glassEdge(cornerRadius = 26.dp, isDark = isDark)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp
            )
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                fontFamily = SnProFamily,
                fontSize = 16.sp,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * Стеклянная плитка-метрика: иконка + caps-label сверху, крупная цифра внизу.
 * Объёмность создаётся за счёт fill, rim и edge-highlight, а не толстого accent-border.
 */
@Composable
internal fun StatsMetricTile(
    value: String,
    label: String,
    accent: Color,
    icon: ImageVector,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 116.dp,
    /** Компактная горизонтальная раскладка (иконка+метка слева, число справа) для невысоких тайлов. */
    horizontal: Boolean = false
) {
    val tileBg = if (isDark) {
        OverlayThemeTokens.TileBackgroundDark
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val labelMuted = if (isDark) {
        OverlayThemeTokens.LabelMutedDark
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tileCorner = OverlayThemeTokens.TileCornerRadius
    val shape = RoundedCornerShape(tileCorner)
    val accentRim = accent.copy(alpha = if (isDark) 0.35f else 0.55f)
    val accentGlow = accent.copy(
        alpha = if (isDark) 0.18f else OverlayThemeTokens.TileGlowAlphaLight
    )

    val visualModifier = modifier
        .defaultMinSize(minHeight = minHeight)
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
        .glassEdge(tileCorner, isDark)
        .border(1.dp, accentRim, shape)

    val iconBox: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isDark) OverlayThemeTokens.TileIconBgDark
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
    val labelText: @Composable (Modifier) -> Unit = { mod ->
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = OverlayThemeTokens.MetricLabel.copy(
                lineBreak = LineBreak.Heading,
                hyphens = Hyphens.None
            ),
            color = labelMuted,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            modifier = mod
        )
    }

    if (horizontal) {
        // Компактно: иконка + метка слева, крупное число справа по вертикальному центру.
        Row(
            modifier = visualModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            iconBox()
            labelText(Modifier.weight(1f))
            Text(
                text = value,
                style = OverlayThemeTokens.MetricValue,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Column(
            modifier = visualModifier.padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                iconBox()
                labelText(Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = value,
                style = OverlayThemeTokens.MetricValue,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
