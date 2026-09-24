package com.example.myapplication.ui.shared.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.iosSheetContainer
import kotlinx.coroutines.launch

/**
 * iOS 26 bottom sheet со всей физикой из гайдбука (глава 2):
 *  • фон («вдавливание»): scale 1→0.92, скругление углов 0→[cornerRadius], затемнение 0→0.35–0.45;
 *  • grab-индикатор 36×5 поверх контента;
 *  • пружина появления [MotionTokens.sheetPresent]; drag-to-dismiss по скорости/смещению (§2.6);
 *  • принудительный дисмисс [MotionTokens.sheetDismissForced] (критическое затухание, без отскока).
 *
 * Это **scaffold**: [content] — экран под шторкой (он масштабируется/затемняется), [sheetContent] —
 * содержимое шторки (заполняет панель; grab-индикатор рисуется поверх него, поэтому для списков
 * добавляй верхний отступ ~[GrabberReservedTop], а полноэкранный контент вроде картинки может
 * уходить под grab-индикатор).
 *
 * @param sheetHeightFraction доля высоты экрана; `null` — панель по высоте контента (medium-детент).
 * @param sheetContainerColor фон панели; `Color.Transparent` если контент рисует свой фон (Details).
 */
@Composable
fun IosSheetScaffold(
    sheetVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetHeightFraction: Float? = 0.92f,
    cornerRadius: Dp = IosDesign.SheetCorner,
    scrimMaxAlpha: Float? = null,
    backgroundScaleTarget: Float = 0.92f,
    dragToDismiss: Boolean = true,
    showGrabber: Boolean = true,
    sheetContainerColor: Color? = null,
    content: @Composable () -> Unit,
    sheetContent: @Composable () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var measuredPanelPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(sheetVisible) {
        if (sheetVisible) {
            dragPx = 0f
            progress.animateTo(1f, MotionTokens.sheetPresent())
        } else {
            progress.animateTo(0f, MotionTokens.sheetDismissForced())
            dragPx = 0f
        }
    }

    // Боковой «провал» за вдавленным экраном. Сверху контент остаётся edge-to-edge
    // (под status bar / вырез камеры) — иначе появляется чёрная полоса в строке состояния.
    Box(modifier = modifier.fillMaxSize().drawBehind { if (progress.value > 0.001f) drawRect(Color.Black) }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenPx = with(density) { maxHeight.toPx() }
            // Прогресс шторки читается только в фазах слоя и отрисовки: раньше он считался прямо
            // в композиции, и каждый кадр анимации/драга пересобирал весь экран под шторкой.
            fun panelHeightPx(): Float = if (measuredPanelPx > 0f) measuredPanelPx
                else screenPx * (sheetHeightFraction ?: 0.9f)
            fun eff(): Float {
                val panelHeightPx = panelHeightPx()
                val dragFraction =
                    if (panelHeightPx > 0f) (dragPx / panelHeightPx).coerceIn(0f, 1f) else 0f
                return (progress.value * (1f - dragFraction)).coerceIn(0f, 1f)
            }
            val sheetShown by remember { derivedStateOf { progress.value > 0.001f } }
            val scrim = scrimMaxAlpha ?: OverlayThemeTokens.scrimAlpha(isDark)

            // --- Фон: scale от верхнего края, скругление только снизу ---
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val eff = eff()
                        val s = lerp(1f, backgroundScaleTarget, eff)
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        clip = eff > 0.001f
                        val bottomR = cornerRadius * eff
                        shape = SquircleCornerShape(
                            topStart = 0.dp,
                            topEnd = 0.dp,
                            bottomEnd = bottomR,
                            bottomStart = bottomR,
                        )
                    }
                    // iOS затемняет фон по-разному: тёмная тема — сильно, светлая — деликатно.
                    .drawWithContent {
                        drawContent()
                        val eff = eff()
                        if (eff > 0f) drawRect(Color.Black, alpha = eff * scrim)
                    },
            ) {
                content()
            }

            // Слой-ловушка тапа по фону → закрыть.
            if (sheetShown) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }

            // --- Панель шторки ---
            val panelShape = SquircleCornerShape(cornerRadius, cornerRadius, 0.dp, 0.dp)
            val heightMod = if (sheetHeightFraction != null) {
                Modifier.fillMaxHeight(sheetHeightFraction)
            } else {
                Modifier.wrapContentHeight()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(heightMod)
                    .heightIn(max = maxHeight * 0.94f)
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { measuredPanelPx = it.height.toFloat() }
                    // Смещение считаем от СВОЕЙ высоты слоя, а не от panelHeightPx: у панели по
                    // высоте контента (sheetHeightFraction = null) на первых кадрах measuredPanelPx
                    // ещё 0, и panelHeightPx подставляет догадку 0.9 экрана. Панель стартовала с
                    // чужого офсета и дёргалась, когда приезжала реальная высота.
                    .graphicsLayer { translationY = (1f - progress.value) * size.height + dragPx }
                    // iOS-глубина шторки (окантовка+блик+градиент+тень) — единый рецепт для всех
                    // непрозрачных панелей. Для прозрачной (Details рисует свой hero) — только clip.
                    .then(
                        if (sheetContainerColor == Color.Transparent) {
                            Modifier.clip(panelShape)
                        } else {
                            Modifier.iosSheetContainer(
                                shape = panelShape,
                                isDark = isDark,
                                base = sheetContainerColor ?: IosDesign.sheetSurface(isDark),
                            )
                        }
                    ),
            ) {
                sheetContent()

                if (showGrabber) {
                    val dragMod = if (dragToDismiss) {
                        Modifier.draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                dragPx = (dragPx + delta).coerceAtLeast(0f)
                            },
                            onDragStopped = { velocity ->
                                val dismiss = MotionTokens.willDismiss(
                                    offset = dragPx,
                                    containerSize = panelHeightPx(),
                                    velocity = velocity / density.density,
                                )
                                if (dismiss) {
                                    onDismiss()
                                } else {
                                    scope.launch {
                                        val start = dragPx
                                        androidx.compose.animation.core.animate(
                                            initialValue = start,
                                            targetValue = 0f,
                                            animationSpec = MotionTokens.sheetPresent(),
                                        ) { v, _ -> dragPx = v }
                                    }
                                }
                            },
                        )
                    } else Modifier
                    GrabberHandle(
                        isDark = isDark,
                        modifier = Modifier.align(Alignment.TopCenter).then(dragMod),
                    )
                }
            }
        }
    }
}

/** Высота, которую резервирует grab-индикатор сверху панели — отступ для списочного контента. */
val GrabberReservedTop: Dp = 22.dp

/**
 * Swipe-down-to-dismiss для «ручных» шторок (те, что рисуются через собственный AnimatedVisibility,
 * а не через [IosSheetScaffold]). Применяй [IosSheetSwipe.panelModifier] к панели (сдвигает её за
 * пальцем) и [IosSheetSwipe.handleModifier] к grab-индикатору. Дисмисс — по смещению >130dp или
 * скорости >900dp/с (§2.6/§2.7), иначе пружинит обратно.
 */
@Composable
fun rememberIosSheetSwipe(onDismiss: () -> Unit): IosSheetSwipe {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val currentOnDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
    }
    val thresholdPx = with(density) { 130.dp.toPx() }
    val panel = Modifier.graphicsLayer { translationY = offset.value }
    val handle = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity ->
            if (offset.value > thresholdPx || velocity / density.density > 900f) {
                currentOnDismiss()
            } else {
                scope.launch { offset.animateTo(0f, MotionTokens.sheetPresent()) }
            }
        },
    )
    return IosSheetSwipe(panel, handle)
}

class IosSheetSwipe(val panelModifier: Modifier, val handleModifier: Modifier)

/** Системный grab-индикатор iOS: 36×5, `systemFill`, отступ 8 сверху (§2.3). */
@Composable
fun GrabberHandle(isDark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GrabberReservedTop),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(width = 36.dp, height = 5.dp)
                .clip(SquircleShape(2.5.dp, smoothing = 0.3f))
                // iOS grabber ≈ tertiary fill: чуть плотнее, чем 0.22, иначе теряется на elevated-панели.
                .background((if (isDark) Color.White else Color.Black).copy(alpha = 0.28f)),
        )
    }
}
