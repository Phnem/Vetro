package com.example.myapplication.ui.workspace

import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.MorphPath
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.rememberStagedMorph
import com.example.myapplication.ui.shared.stagedMorphBounds
import com.example.myapplication.ui.shared.stagedMorphContentScale
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/** Пункт меню ТТМ: иконку рисует вызывающий — она у каждого своя, в том числе из ресурсов. */
@Immutable
data class TtmMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val icon: @Composable (tint: Color) -> Unit,
)

private val MENU_WIDTH = 210.dp
private val ROW_HEIGHT = 48.dp
private val MENU_PADDING = 8.dp
private val MENU_CORNER = 26.dp

/**
 * Меню последнего гнезда дока — то, что на референсе раскрывается из кнопки «…».
 *
 * Собрано на той же механике, что морф панели статистики (`StagedSheetMotion.kt`), и по тем же
 * правилам: объект не возникает из ниоткуда, у него есть геометрический источник — кнопка, из
 * которой он вырос. Отличий от панели два, и оба из референса:
 *
 * * **Якорь — нижний правый угол.** Панель растёт вверх и влево, оставаясь пришитой к кнопке;
 *   центрировать её по экрану значило бы порвать связь с источником.
 * * **Путь прямой ([MorphPath.DIRECT]), без промежуточной формы.** Сборка в промежуточную форму
 *   осмысленна для панели, раскрывающейся в центр экрана; здесь же у объекта и так есть видимый
 *   источник — кнопка под ним. Остановка на полпути превращала бы одно движение вверх в два:
 *   топтание на месте, потом рывок.
 * * **Строки проявляются каскадом.** Не все разом: каждая следующая отстаёт на
 *   [ROW_STAGGER_FRACTION] доли раскрытия. Ровно то, что видно в референсе, и ровно то, что
 *   мешает прочитать меню как «просто появившийся прямоугольник».
 *
 * Пока оболочка мала, содержимое прозрачно и слегка сжато: втискивать текст в форму, которая его
 * не вмещает, нельзя — буквы режет.
 */
@Composable
fun TtmMenu(
    expanded: Boolean,
    origin: Rect?,
    items: List<TtmMenuItem>,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val isDark = isAppInDarkTheme()
    val material = FrostedMaterials.dock()
    val morph = rememberStagedMorph(expanded, MorphPath.DIRECT)
    var containerBounds by remember { mutableStateOf<Rect?>(null) }

    // Полностью свёрнутое и невидимое меню не должно перехватывать касания по экрану.
    // Через derivedStateOf: сам прогресс меняется каждый кадр, а композиция нужна только на пороге.
    val interactive by remember { derivedStateOf { morph.shell > 0.01f } }
    if (!expanded && !interactive) {
        // Узел всё равно остаётся в дереве: измеритель контейнера обязан пережить закрытие,
        // иначе следующее открытие первым кадром не знает, куда расти.
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { containerBounds = it.boundsInRoot() },
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(9f)
            .onGloballyPositioned { containerBounds = it.boundsInRoot() },
    ) {
        // Затемнение под меню: оно же закрывает меню тапом мимо. Прозрачность — свойство света,
        // поэтому едет своей дорожкой, не пружиной.
        val scrimAlpha = animateFloatAsState(
            targetValue = if (expanded) if (isDark) 0.42f else 0.22f else 0f,
            label = "ttmScrim",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black, alpha = scrimAlpha.value) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        val container = containerBounds
        if (container != null && origin != null) {
            val menuWidthPx = with(density) { MENU_WIDTH.toPx() }
            val menuHeightPx = with(density) {
                (ROW_HEIGHT * items.size + MENU_PADDING * 2).toPx()
            }
            // Правый край меню совпадает с правым краем кнопки, низ — чуть выше неё. Меню
            // остаётся пришитым к источнику, как на референсе.
            val gapPx = with(density) { 10.dp.toPx() }
            val target = Rect(
                left = origin.right - menuWidthPx,
                top = origin.top - gapPx - menuHeightPx,
                right = origin.right,
                bottom = origin.top - gapPx,
            )
            // Координаты источника и цели — абсолютные (boundsInRoot), а рисуем внутри своего
            // контейнера: переводим в его систему, иначе меню уедет на величину отступов.
            // Считается в фазе раскладки: прогресс морфа меняется каждый кадр, и чтение в
            // композиции пересобирало бы меню целиком на каждом кадре раскрытия.
            fun bounds(): Rect = stagedMorphBounds(morph.shell, origin, target, MorphPath.DIRECT)
                .translate(-container.left, -container.top)
            val menuShape = RoundedCornerShape(MENU_CORNER)

            Box(
                modifier = Modifier
                    .layout { measurable, _ ->
                        val b = bounds()
                        val w = b.width.roundToInt().coerceAtLeast(0)
                        val h = b.height.roundToInt().coerceAtLeast(0)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) {
                            placeable.place(b.left.roundToInt(), b.top.roundToInt())
                        }
                    }
                    .clip(menuShape)
                    .frostedGlass(backdrop = backdrop, shape = menuShape, material = material),
                // Содержимое прижато к тому же углу, из которого растёт оболочка. С выравниванием
                // по верхнему краю растущая панель показывала бы ПЕРВУЮ строку и дорисовывала
                // остальные вниз — ровно против каскада, который идёт снизу вверх.
                contentAlignment = Alignment.BottomEnd,
            ) {
                Column(
                    modifier = Modifier
                        .width(MENU_WIDTH)
                        .padding(vertical = MENU_PADDING)
                        .graphicsLayer {
                            alpha = morph.contentAlpha
                            val s = stagedMorphContentScale(morph.shell)
                            scaleX = s
                            scaleY = s
                            // Содержимое растёт из нижнего правого угла вместе с оболочкой:
                            // масштаб от центра отрывал бы текст от края, к которому он прижат.
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
                        },
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        TtmMenuRow(
                            item = item,
                            // Каскад: нижние строки ближе к кнопке, поэтому и проявляются первыми.
                            revealProgress = {
                                rowReveal(shell = morph.shell, indexFromBottom = items.lastIndex - index)
                            },
                            isDark = isDark,
                            onClick = {
                                onDismiss()
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** На сколько доли раскрытия отстаёт каждая следующая строка. */
private const val ROW_STAGGER_FRACTION = 0.07f

/** Насколько раскрытой должна быть оболочка, чтобы первая строка начала проявляться. */
private const val ROW_REVEAL_START = 0.45f

/** Прозрачность строки по прогрессу оболочки — см. каскад в kdoc [TtmMenu]. */
internal fun rowReveal(shell: Float, indexFromBottom: Int): Float {
    val start = ROW_REVEAL_START + ROW_STAGGER_FRACTION * indexFromBottom
    val span = 0.22f
    return ((shell - start) / span).coerceIn(0f, 1f)
}

@Composable
private fun TtmMenuRow(
    item: TtmMenuItem,
    revealProgress: () -> Float,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val content = if (isDark) Color.White else Color(0xFF1C1C1E)
    Row(
        modifier = Modifier
            // Именно ширину: fillMaxSize отдаёт строке ВСЮ высоту колонки, и соседним строкам
            // не остаётся ничего — в меню видно ровно один пункт.
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                val reveal = revealProgress()
                alpha = reveal
                // Небольшой подъезд снизу — строка приезжает вместе с оболочкой, а не проявляется
                // на месте. Величина маленькая: это акцент на порядке, а не отдельное движение.
                translationY = (1f - reveal) * 10.dp.toPx()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            item.icon(content.copy(alpha = 0.85f))
        }
        Box(Modifier.width(12.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
    }
}
