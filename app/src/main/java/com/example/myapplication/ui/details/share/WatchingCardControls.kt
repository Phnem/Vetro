package com.example.myapplication.ui.details.share

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Круглая кнопка-выключатель с рисованной иконкой: тот же язык, что у колокольчика уведомлений в Details.
 * Включённая — оранжевая с белой иконкой, выключенная — нейтральная с тонкой каймой. Иконка получает
 * прогресс `0..1`, который идёт за состоянием на пружине [MotionTokens.menuPop] и слегка перелетает,
 * поэтому смена вида читается как движение, а не как замена картинки.
 */
@Composable
internal fun RoundIconToggle(
    on: Boolean,
    onChange: (Boolean) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    draw: DrawScope.(progress: Float, tint: Color) -> Unit,
) {
    val reduced = rememberReducedMotion()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(if (on) 1f else 0f) }
    val press = remember { Animatable(1f) }
    LaunchedEffect(on) {
        val target = if (on) 1f else 0f
        if (reduced) progress.snapTo(target) else progress.animateTo(target, MotionTokens.menuPop())
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val bg by animateColorAsState(if (on) BrandOrange else onSurface.copy(alpha = 0.08f), tween(COLOR_MILLIS), label = "toggleBg")
    val fg by animateColorAsState(if (on) Color.White else onSurface, tween(COLOR_MILLIS), label = "toggleFg")

    Box(
        modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, if (on) Color.Transparent else onSurface.copy(alpha = 0.12f), CircleShape)
            .toggleable(
                value = on,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
            ) { next ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (!reduced) {
                    scope.launch {
                        press.snapTo(0.86f)
                        press.animateTo(1f, MotionTokens.limitPop())
                    }
                }
                onChange(next)
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) { draw(progress.value, fg) }
    }
}

/**
 * Иконка «объём данных»: карточка со строками. При `progress = 1` она высокая, с тремя строками; при 0
 * сжимается до заголовка. Движение повторяет то, что происходит с картинкой в предпросмотре.
 */
internal fun DrawScope.drawDetailLevelIcon(progress: Float, tint: Color) {
    val t = progress.coerceAtLeast(0f)
    val k = size.width / 24f
    val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    scale(k, k, pivot = Offset.Zero) {
        // Карточка сжимается к верху; сдвиг держит её по центру кнопки в обоих состояниях.
        translate(0f, (1f - t) * 3.5f) {
            val bottom = 14f + 7f * t
            drawRoundRect(tint, Offset(4f, 3f), Size(16f, bottom - 3f), CornerRadius(3f), style = stroke)
            drawLine(tint, Offset(8f, 8f), Offset(16f, 8f), 2f, StrokeCap.Round)
            val lines = t.coerceIn(0f, 1f)
            if (lines > 0.02f) {
                val faded = tint.copy(alpha = tint.alpha * lines)
                drawLine(faded, Offset(8f, 12.5f), Offset(8f + 8f * lines, 12.5f), 2f, StrokeCap.Round)
                drawLine(faded, Offset(8f, 16.5f), Offset(8f + 5f * lines, 16.5f), 2f, StrokeCap.Round)
            }
        }
    }
}

private val CommentBody: Path by lazy {
    PathParser().parsePathString("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z").toPath()
}
private val CommentSlash: Path by lazy { PathParser().parsePathString("M3 3l18 18").toPath() }
private const val SLASH_LEN = 26f

/**
 * Иконка «комментарий на карточке»: пузырь с двумя строками. Выключенный перечёркнут косой чертой, которая
 * «стирается» вдоль себя при включении (как у колокольчика), а строки внутри пузыря дописываются.
 */
internal fun DrawScope.drawCommentIcon(progress: Float, tint: Color) {
    val t = progress.coerceIn(0f, 1f)
    val k = size.width / 24f
    val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    scale(k, k, pivot = Offset.Zero) {
        drawPath(CommentBody, tint, style = stroke)
        if (t > 0.02f) {
            val faded = tint.copy(alpha = tint.alpha * t)
            drawLine(faded, Offset(8f, 9f), Offset(8f + 8f * t, 9f), 2f, StrokeCap.Round)
            drawLine(faded, Offset(8f, 13f), Offset(8f + 5f * t, 13f), 2f, StrokeCap.Round)
        }
        if (t < 0.98f) {
            drawPath(
                CommentSlash,
                tint,
                style = Stroke(
                    width = 2f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(SLASH_LEN, SLASH_LEN), SLASH_LEN * t),
                ),
            )
        }
    }
}

/** Капсула с ником: тап открывает шторку ввода (то же поле с пределом, что у комментария). */
@Composable
internal fun NickPill(
    nick: String,
    placeholder: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(onSurface.copy(alpha = 0.08f), CircleShape)
            .border(1.dp, onSurface.copy(alpha = 0.12f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.Person,
            contentDescription = null,
            tint = if (nick.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else BrandOrange,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = nick.ifEmpty { placeholder },
            fontFamily = SnProFamily,
            fontSize = 14.sp,
            fontWeight = if (nick.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
            color = if (nick.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Живой предпросмотр карточки. Картинки здесь нет: карточка рисуется заново каждый кадр по двум значениям,
 * которые идут на пружинах. [full] ведёт «объём данных» (1 — всё, 0 — минимум): блоки под обложкой растут и
 * сжимаются, текст всплывает и гаснет, подвал едет следом, а фон тянется вместе с холстом, потому что
 * его пятна привязаны к долям высоты. [showBubble] ведёт пузырь-комментарий: он вырастает облачком из
 * «хвоста» у логотипа, а холст сверху растягивается под него. Высота читается в фазе раскладки и рисуется в
 * фазе отрисовки, так что кадр не пересобирает композицию. Итоговый PNG рисуется тем же кодом.
 */
@Composable
internal fun CardPreview(
    data: ShareCardData?,
    full: Boolean,
    showBubble: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    width: Dp = PREVIEW_WIDTH,
) {
    val reduced = rememberReducedMotion()
    val detail = remember { Animatable(if (full) 1f else 0f) }
    val bubble = remember { Animatable(if (showBubble) 1f else 0f) }
    LaunchedEffect(full) {
        val target = if (full) 1f else 0f
        if (reduced) detail.snapTo(target) else detail.animateTo(target, MotionTokens.standard())
    }
    LaunchedEffect(showBubble) {
        val target = if (showBubble) 1f else 0f
        if (reduced) bubble.snapTo(target) else bubble.animateTo(target, MotionTokens.menuPop())
    }
    // Раскладки текста и палитра считаются один раз на набор данных; кадры только рисуют.
    val scene = remember(data) { data?.let { WatchingCardRenderer.Scene(it) } }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        if (scene == null) {
            Box(Modifier.width(width).height(width * PLACEHOLDER_ASPECT), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandOrange, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            }
        } else {
            Canvas(
                Modifier
                    .width(width)
                    .layout { measurable, constraints ->
                        val w = constraints.maxWidth
                        val pose = WatchingCardRenderer.Pose(detail.value, bubble.value)
                        val h = (w * scene.heightUnits(pose) / WatchingCardRenderer.CANVAS_UNITS).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) { placeable.place(0, 0) }
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .semantics { contentDescription = description },
            ) {
                val pose = WatchingCardRenderer.Pose(detail.value, bubble.value)
                drawIntoCanvas { canvas ->
                    scene.draw(canvas.nativeCanvas, size.width / WatchingCardRenderer.CANVAS_UNITS, pose)
                }
            }
        }
    }
}

/** Ширина предпросмотра: полная карточка с пузырём при ней укладывается в высоту шторки. */
private val PREVIEW_WIDTH = 264.dp

/** Пока картинки нет, под неё резервируется место средней карточки. */
private const val PLACEHOLDER_ASPECT = 1.3f
private const val COLOR_MILLIS = 200
