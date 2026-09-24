package com.example.myapplication.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.stagedMorphOrigin
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/**
 * Один пункт капсульного дока. Подписи у него нет — см. kdoc [CapsuleGlassDock].
 *
 * Иконку рисует ВЫЗЫВАЮЩИЙ, а не док. Это не абстракция ради абстракции: у половины пунктов
 * иконка участвует в shared-element переходе в своё окно (`sharedBounds`/`sharedElement`), а
 * ключи и режимы пересчёта у каждого свои и живут там же, где объявлены области перехода.
 * Док, принимающий `ImageVector`, эти переходы молча терял — окна открывались без анимации.
 */
@Immutable
data class CapsuleDockItem(
    val contentDescription: String,
    val onClick: () -> Unit,
    /**
     * Пункт открывает панель, которая раскрывается ИЗ него (см. `StagedSheetMotion.kt`).
     *
     * Границы такого гнезда публикуются наружу — панели нужно знать, откуда выходить.
     */
    val isMorphOrigin: Boolean = false,
    /**
     * Куда сообщать границы гнезда, если из него растёт что-то своё.
     *
     * Отдельно от [isMorphOrigin]: та точка одна на приложение и принадлежит панелям, а меню дока
     * растёт из СВОЕГО гнезда и делить с ними общее состояние не может — иначе последний
     * измеренный элемент затирал бы чужую точку выхода.
     */
    val reportBounds: ((Rect) -> Unit)? = null,
    /** @param tint цвет содержимого: акцентный у подсвеченного пункта, приглушённый у остальных. */
    val icon: @Composable (tint: Color) -> Unit,
)

private val DOCK_HEIGHT = 58.dp

/**
 * Сколько места снизу занимает капсульный док.
 *
 * Отдельно от инсета прежнего дока: капсула ниже на 16dp, и если страницы продолжат резервировать
 * место под старую высоту, под каждой из них останется полоса пустоты — та самая, из-за которой
 * список настроек прокручивался заметно ниже последнего пункта.
 */
val CapsuleDockInset: Dp = DOCK_HEIGHT + 16.dp + 12.dp

/** Ширина гнезда. Из неё же считается ширина всей капсулы — см. kdoc [CapsuleGlassDock]. */
private val SLOT_WIDTH = 52.dp

/** Диаметр подсветки активного гнезда. */
private val SLOT_SIZE = 44.dp
private val DOCK_SIDE_PADDING = 6.dp

/**
 * Капсульный док из матового стекла.
 *
 * Отличий от прежнего дока три, и каждое — следствие правил дизайн-документа, а не вкуса.
 *
 * **Материал матовый, а не линзовый.** Прежний док построен на «жидком» стекле: размытие почти
 * нулевое, зато сильная линза — контент под ним увеличивается и читается насквозь. Здесь наоборот,
 * диффузия (см. `FrostedGlass.kt`): под доком едет список обложек, и размытие честно показывает,
 * что капсула лежит ПОВЕРХ него. Стеклу при этом нужна информация под собой — над сплошной
 * заливкой оно выродится в непрозрачный прямоугольник, поэтому док и живёт над списком.
 *
 * **Подписей нет.** Иконка с подписью под ней — это два уровня в высоте одного пункта; капсула
 * из-за этого вырастает и перестаёт быть капсулой. Роль пункта показывает подсветка, а не текст.
 *
 * **Подсветка одна и подвижная.** Не «у каждого пункта свой фон, у активного — ярче», а ровно
 * одна капсула, переезжающая между гнёздами пружиной: объект не телепортируется, а перемещается,
 * и по дороге видно, откуда и куда. Переезд идёт по координате (пространство → пружина), а
 * появление и цвет — коротким затуханием (свет → easing).
 *
 * Круглая кнопка справа от капсулы — отдельная поверхность того же материала. Она не пункт
 * навигации, а самостоятельное действие, и разделение форм читается быстрее любой подписи.
 *
 * **Ширина — по содержимому, а не по экрану.** Капсула ровно на свои гнёзда: плавающая панель тем
 * и отличается от полосы навигации, что не притворяется краем экрана. Растянутая во всю ширину,
 * она читается как приклеенная снизу панель, и запас пустоты внутри ничем не оправдан — по нему
 * нечего нажимать.
 *
 * @param selectedIndex какое гнездо подсвечено; `null` — ни одного. Классический док не
 *   соответствует постоянному разделу (его пункты открывают окна и оверлеи), поэтому подсветка
 *   отмечает последнее активированное гнездо — вызывающий решает, что считать активным.
 */
@Composable
fun CapsuleGlassDock(
    backdrop: Backdrop,
    items: List<CapsuleDockItem>,
    selectedIndex: Int?,
    trailingButton: CapsuleDockItem?,
    trailingActive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val isDark = isAppInDarkTheme()
    val material = FrostedMaterials.dock()
    val dockShape = RoundedCornerShape(DOCK_HEIGHT / 2)
    val idleTint = if (isDark) Color.White.copy(alpha = 0.72f) else Color(0xFF1C1C1E)

    // Капсула и круглая кнопка стоят В ОДНУ СТРОКУ, как на референсе: это две поверхности
    // одного уровня, а не кнопка, висящая над доком. Ряд, а не наложение в Box, — тогда ширину
    // капсулы задаёт остаток строки и они не наезжают друг на друга ни на одном экране.
    val capsuleWidth = SLOT_WIDTH * items.size + DOCK_SIDE_PADDING * 2
    val highlightInset = (SLOT_WIDTH - SLOT_SIZE) / 2

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TRAILING_GAP, Alignment.CenterHorizontally),
    ) {
        Box(
            modifier = Modifier
                .width(capsuleWidth)
                .height(DOCK_HEIGHT)
                .clip(dockShape)
                .frostedGlass(backdrop = backdrop, shape = dockShape, material = material),
        ) {
            if (selectedIndex != null) {
                SlidingHighlight(
                    index = selectedIndex,
                    stride = SLOT_WIDTH,
                    leading = DOCK_SIDE_PADDING + highlightInset,
                    isDark = isDark,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = DOCK_SIDE_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    DockSlot(
                        item = item,
                        width = SLOT_WIDTH,
                        tint = if (index == selectedIndex) BrandOrange else idleTint,
                    )
                }
            }
        }

        if (trailingButton != null) {
            val trailingShape = CircleShape
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.90f else 1f,
                animationSpec = MotionTokens.pressFloat,
                label = "dockTrailingPress",
            )
            Box(
                modifier = Modifier
                    // Ровно высота капсулы: два круга разного диаметра в одной строке читаются
                    // как ошибка вёрстки, а не как замысел.
                    .size(DOCK_HEIGHT)
                    .clip(trailingShape)
                    .frostedGlass(backdrop = backdrop, shape = trailingShape, material = material)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = trailingButton.onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    trailingButton.icon(if (trailingActive) BrandOrange else idleTint)
                }
            }
        }
    }
}

/** Зазор между капсулой и отдельным пузырьком справа. */
private val TRAILING_GAP = 10.dp

/**
 * Подсветка активного гнезда.
 *
 * Едет через `offset` (раскладкой), а не через `translationX` в `graphicsLayer`: узел лежит внутри
 * поверхности, которая сэмплит бэкдроп, и сдвиг слоем в этой кодовой базе роняет стекло в плоскую
 * заливку.
 */
@Composable
private fun SlidingHighlight(
    index: Int,
    stride: Dp,
    leading: Dp,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetX = leading + stride * index
    val animatedX by animateFloatAsState(
        targetValue = targetX.value,
        // Перемещение — пространство, значит пружина. Та же, что у раскрытия капсулы в доке
        // рабочей области: два дока не должны двигаться по-разному.
        animationSpec = MotionTokens.sheetPresent(),
        label = "dockHighlightX",
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fill = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.82f)

    Box(
        modifier = modifier
            .offset { IntOffset(with(density) { animatedX.dp.toPx() }.roundToInt(), 0) }
            .size(SLOT_SIZE)
            .clip(CircleShape)
            .background(fill),
    )
}

@Composable
private fun DockSlot(
    item: CapsuleDockItem,
    width: Dp,
    tint: Color,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Нажатие сжимает, а не увеличивает: палец уже закрывает иконку, и рост из-под пальца
    // выглядит как промах. Пружина короткая и упругая.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = MotionTokens.pressFloat,
        label = "dockSlotPress",
    )

    Box(
        modifier = Modifier
            .width(width)
            .height(SLOT_SIZE)
            .then(if (item.isMorphOrigin) Modifier.stagedMorphOrigin() else Modifier)
            .then(
                item.reportBounds?.let { report ->
                    Modifier.onGloballyPositioned { report(it.boundsInRoot()) }
                } ?: Modifier,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = item.onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            contentAlignment = Alignment.Center,
        ) {
            item.icon(tint)
        }
    }
}
