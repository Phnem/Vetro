package com.example.myapplication.ui.shared.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.iosSheetContainer
import kotlinx.coroutines.flow.first

/**
 * Нижняя шторка со скримом: выезжает снизу и уходит вниз, по законам
 * `UNIVERSAL_MOTION_SPEC.md`.
 *
 * * **Пружины — для пространства, затухание — для света** (закон 2). Выезд — пружина крупной
 *   поверхности ([MotionTokens.largeSurfaceEnter]); скрим и содержимое — короткие затухания.
 * * **Закрытие освобождает дорогу** (закон 5): уход — своя пружина, критически затухающая и
 *   быстрее входа, без отскока у нижней кромки.
 * * **Оболочка и содержимое — разные траектории** (§5). Панель едет целиком, но содержимое
 *   проявляется, только когда она почти приехала, и гаснет первым, как только её отпустили: на
 *   экране не бывает «летящего текста».
 * * **Прерываемость** (закон 3): движение ведёт `Transition`, поэтому повторное открытие посреди
 *   ухода подхватывает текущую позицию и скорость, а не прыгает к началу.
 * * **Только композитор** (закон 7): панель свёрстана один раз в конечный размер, анимируются
 *   лишь сдвиг и прозрачность слоя.
 *
 * Жизненный цикл отдаётся через [visibleState], как у `AnimatedVisibility`: вызывающий снимает
 * шторку, когда `currentState` и `targetState` оба `false`.
 *
 * @param swipe смахивание вниз; сдвиг пальцем складывается с выездом, и уход продолжается с того
 *   места, куда шторку успели стянуть.
 * @param panelModifier размеры панели (`fillMaxWidth`, `heightIn` и т. п.).
 */
@Composable
fun MotionBottomSheet(
    visibleState: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    panelModifier: Modifier = Modifier,
    swipe: IosSheetSwipe? = null,
    scrimAlpha: Float = OverlayThemeTokens.scrimAlpha(isDark),
    topCorner: Dp = IosDesign.SheetCorner,
    containerColor: Color = IosDesign.sheetSurface(isDark),
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val transition = rememberTransition(visibleState, label = "motionSheet")
    // 0 — шторка за нижней кромкой, 1 — на месте.
    val shell = transition.animateFloat(
        transitionSpec = {
            when {
                reducedMotion -> snap()
                targetState -> MotionTokens.largeSurfaceEnter()
                else -> MotionTokens.largeSurfaceExit()
            }
        },
        label = "motionSheetShell",
    ) { shown -> if (shown) 1f else 0f }
    val scrim = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(MotionTokens.ScrimFadeMillis, easing = MotionTokens.EaseEnter)
            } else {
                tween(MotionTokens.ScrimExitMillis, easing = MotionTokens.EaseExit)
            }
        },
        label = "motionSheetScrim",
    ) { shown -> if (shown) 1f else 0f }

    // Содержимое — отдельная дорожка на затухании: оно ждёт оболочку, а не едет с ней вровень.
    val contentAlpha = remember { Animatable(0f) }
    LaunchedEffect(visibleState.targetState) {
        if (visibleState.targetState) {
            snapshotFlow { shell.value }.first { it >= CONTENT_REVEAL_PROGRESS }
            contentAlpha.animateTo(1f, tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter))
        } else {
            contentAlpha.animateTo(0f, tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit))
        }
    }

    var panelHeight by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrim.value }
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        val shape = SquircleCornerShape(topCorner, topCorner, 0.dp, 0.dp)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(panelModifier)
                .onSizeChanged { panelHeight = it.height.toFloat() }
                .then(swipe?.panelModifier ?: Modifier)
                .graphicsLayer {
                    if (reducedMotion) {
                        // Пониженное движение (§7): без сдвигов, только проявление.
                        alpha = contentAlpha.value
                        return@graphicsLayer
                    }
                    // Пока высота не измерена, панель прячется: иначе первый кадр показал бы
                    // её на месте, а следующий — отдёрнул бы вниз.
                    alpha = if (panelHeight > 0f) 1f else 0f
                    // Запас под тень: иначе в нулевой точке над кромкой торчала бы её полоска.
                    val travel = panelHeight + SHADOW_ALLOWANCE.toPx()
                    translationY = (1f - shell.value) * travel
                }
                .iosSheetContainer(shape, isDark, containerColor),
        ) {
            Column(modifier = Modifier.graphicsLayer { alpha = contentAlpha.value }) {
                content()
            }
        }
    }
}

/**
 * Доля пути, после которой проявляется содержимое (§5, «кадр 50–100 мс»): панель уже почти
 * приехала, и текст появляется на месте, а не летит снизу. У пружины крупной поверхности это
 * ≈150 мс от старта.
 */
private const val CONTENT_REVEAL_PROGRESS = 0.6f

private val SHADOW_ALLOWANCE = 24.dp
