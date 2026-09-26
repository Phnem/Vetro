package com.example.myapplication.ui.shared.theme

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.ui.Modifier.Node
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch

/**
 * iOS design-токены (гайдбук §3): радиусы, материалы, системная палитра сгруппированных списков.
 * Хекс-значения из §3.3 замаплены в light/dark пары; в UI обращайся к семантическим полям, а не
 * к сырым Color(...).
 */
object IosDesign {

    // ---- Радиусы (§3.1). Всегда через SquircleShape, не голый RoundedCornerShape. ----
    val RadiusXs = 8.dp      // чипы, микро-контролы, внутренние элементы списков
    val RadiusSm = 12.dp     // кнопки, иконки дока
    val RadiusMd = 18.dp     // карточки, диалоги, контекстные меню (16–20dp)
    val RadiusLg = 26.dp     // док-панели, полноэкранные карточки (24–28dp)

    /**
     * Верхние углы bottom sheet. Намеренно крупнее [RadiusLg]: на новых iOS шторки имеют
     * заметно более «пухлое» скругление верхних углов, чем карточки (ср. Set Status / picker).
     * Единый источник правды — все панели-шторки берут радиус отсюда.
     */
    val SheetCorner = 40.dp

    /**
     * Верхний отступ содержимого шторки: резерв под grab-индикатор (22dp) плюс воздух.
     * Общий для всех панелей, чтобы контент везде начинался на одной высоте.
     */
    val SheetContentTop = 30.dp

    /** iOS icon-well для строки списка: контейнер 32dp, скругление 9dp, белая пиктограмма 18dp. */
    val ListIconSize = 32.dp
    val ListIconRadius = 9.dp
    val ListIconGlyph = 18.dp

    /**
     * Единая палитра акцентов icon-well по СМЫСЛОВЫМ категориям (не случайные цвета).
     * Один цвет = одна категория во всём приложении. Saturation ~60–75%, спокойные тона.
     */
    // Монохромно-оранжевая брендовая гамма: категории различаются оттенком/светлотой
    // внутри одной тёплой палитры (#E85002 + серые), белая пиктограмма остаётся читаемой.
    object Category {
        val Account = BrandOrange      // фирменный оранжевый
        val Cloud = BrandGray        // серый (синхронизация/облако)
        val Language = BrandOrange     // обожжённая глина
        val Appearance = BrandOrangeBright   // яркий оранжевый (внешний вид)
        val Media = BrandDarkGray        // тёмно-серый (тип контента/медиа)
        val Notifications = BrandDeepRed // глубокий красный
        val Security = BrandDeepRed     // глубокий красный
        val Network = BrandOrange      // тёмный песочный
        val Ai = Color(0xFF8A8A8E)           // средне-серый
        val Storage = Color(0xFF77716C)      // тёплый серый
        val Update = BrandOrange       // янтарный
        val Support = BrandOrange      // светлая глина (связь/поддержка)
        val Donate = Color(0xFFD93A2B)       // алый (осветлённый #C10801)
    }

    fun squircleMd() = SquircleShape(RadiusMd)
    fun squircleLg() = SquircleShape(RadiusLg)
    fun squircleSm() = SquircleShape(RadiusSm)

    // ---- Материалы уровня 2 (§3.2) — дешёвая alpha-поверхность без рефракции. ----
    fun level2Surface(isDark: Boolean): Color =
        if (isDark) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.85f)

    /** Scrim по умолчанию (§3.2): чёрный, alpha 0.30–0.45; для алертов — 0.4 (§10). */
    fun scrim(alpha: Float = 0.4f): Color = Color.Black.copy(alpha = alpha)

    // ---- Палитра сгруппированного списка. Фирменная монохромная гамма:
    // чёрный базовый фон + нейтрально-серые ступени для карточек. ----
    /** Фон экрана за списком: чёрный [DarkBackground] (#000000) / светлый [LightBackground]. */
    fun groupedBackground(isDark: Boolean): Color =
        if (isDark) DarkBackground else LightBackground

    /**
     * Фон экрана-списка с тёплой брендовой засветкой сверху: настройки, меню серий, меню глав.
     *
     * Одно значение на три экрана, а не литерал в каждом: две копии уже успели разъехаться в
     * светлой теме (#FFD9CC против #FFD8CB), и третья закрепила бы расхождение как норму.
     *
     * К первой трети экрана градиент растворяется в фоне темы — дальше список идёт по обычному
     * фону, и карточки на нём читаются как всегда.
     */
    fun screenGradient(isDark: Boolean): Brush {
        val bg = groupedBackground(isDark)
        return if (isDark) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF430B05),
                    0.24f to Color(0xFF210907),
                    0.58f to bg,
                    1f to bg,
                ),
            )
        } else {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFFFFD8CB),
                    0.28f to Color(0xFFFFEEE8),
                    0.64f to bg,
                    1f to bg,
                ),
            )
        }
    }

    /** Фон карточки/строки: серая ступень [OverlayThemeTokens.TileBackgroundDark] / белый. */
    fun rowBackground(isDark: Boolean): Color =
        if (isDark) OverlayThemeTokens.TileBackgroundDark else LightSurface

    /**
     * Контейнер группы под карточками строк — «карточка в карточке». На ступень ТЕМНЕЕ строк
     * в тёмной теме и на ступень темнее белого в светлой: сами строки должны читаться выше
     * контейнера, а не сливаться с ним.
     */
    fun groupBackground(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.035f)

    /**
     * Заливка карточки строки ВНУТРИ хаба. Намеренно полупрозрачная, а не [rowBackground]:
     * непрозрачный серый не реагирует на подложку экрана, и на тонированном фоне настроек
     * карточки оставались нейтрально-серыми, пока сам хаб тон уже подхватил.
     * Кладётся поверх [groupBackground], поэтому суммарно карточка на ступень светлее хаба.
     */
    fun groupRowBackground(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.055f) else Color.White.copy(alpha = 0.55f)

    /** Скругление контейнера группы: на ступень крупнее карточки строки ([RadiusMd]). */
    val GroupRadius = 24.dp

    // ---- Метрики карточки-строки. Карточка сама себе контейнер, поэтому у неё собственные
    // поля и типографика: воздуха больше, чем у строки сплошного списка, иконка крупнее и
    // плоская, заголовок полужирный, значение и chevron — приглушённые и мельче. ----
    val CardHorizontalInset = 18.dp
    val CardVerticalInset = 14.dp
    val CardMinHeight = 64.dp
    val CardIconSize = 28.dp
    val CardIconGlyph = 24.dp
    val CardIconTextGap = 16.dp
    val CardChevronSize = 18.dp
    /** Поля контейнера группы вокруг карточек строк. */
    val GroupPadding = 6.dp
    /** Зазор между соседними карточками строк внутри группы. */
    val GroupRowSpacing = 6.dp

    /**
     * Поверхность bottom sheet — iOS elevated-палитра (HIG Dark Mode / WWDC19 §Semantic colors):
     * системная шторка в тёмной теме рисуется НЕ base-фоном, а elevated-цветом на ступень светлее
     * (у Apple: base #000 → sheet #1C1C1E → группы #2C2C2E). Перенос на монохромную гамму Vetro:
     * base #000000 → sheet #111111 → группы/строки #171717 ([rowBackground]) — иерархия
     * «затемнённый фон < панель < карточки» сохраняется автоматически на всех шторках.
     * Light: iOS grouped-sheet = #F2F2F7-фон с белыми группами → наш [LightBackground].
     */
    fun sheetSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF111111) else LightBackground

    /** Заливка вложенной карточки-опции поверх [rowBackground] (ещё на тон светлее). */
    fun elevatedCard(isDark: Boolean): Color =
        if (isDark) DarkSurfaceVariant else Color(0xFFF0F0F0)

    /**
     * Разделитель. Android-поправка (§3.3): 1dp сплошная линия с alpha ~0.15–0.2 вместо
     * попытки попасть в суб-1dp iOS 0.5pt.
     */
    fun separator(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.16f)

    val SeparatorThickness = 1.dp

    /** Отступ строки-контента и, соответственно, левый inset разделителя (§8): 16 + 29 + 12. */
    val ListHorizontalInset = 16.dp
    val ListIconTextGap = 12.dp
    val SeparatorInset = 16.dp + 32.dp + 12.dp

    /** Chevron / вторичные иконки: #C7C7CC. */
    fun chevron(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.30f) else Color(0xFFC7C7CC)

    /** Highlight при нажатии строки: light #D1D1D6 / тональный dark аналог (§3.3, §8). */
    fun rowHighlight(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFD1D1D6).copy(alpha = 0.9f)
}

/**
 * iOS instant-highlight (гайдбук §8): мгновенная заливка фона строки на нажатие + плавный
 * fade-out при отпускании за 150–200ms. **Без Ripple** — это ключевое отличие от Material.
 *
 * Использование: `Modifier.clickable(interactionSource = src, indication = rememberIosHighlight())`.
 */
class IosHighlightIndication(
    private val color: Color,
    private val shape: Shape,
    private val fadeOutMillis: Int = 180,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        IosHighlightNode(interactionSource, color, shape, fadeOutMillis)

    override fun equals(other: Any?): Boolean =
        other is IosHighlightIndication &&
            other.color == color &&
            other.shape == shape &&
            other.fadeOutMillis == fadeOutMillis

    override fun hashCode(): Int =
        (color.hashCode() * 31 + shape.hashCode()) * 31 + fadeOutMillis
}

private class IosHighlightNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
    private val shape: Shape,
    private val fadeOutMillis: Int,
) : Node(), DrawModifierNode {

    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        // Мгновенная заливка — без tween на входе (§8).
                        alpha.snapTo(1f)
                        invalidateDraw()
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        launch {
                            alpha.animateTo(0f, tween(fadeOutMillis)) { invalidateDraw() }
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        // Highlight рисуется ПОД контентом строки (иначе перекрыл бы текст/иконку).
        val a = alpha.value
        if (a > 0f) {
            val fill = color.copy(alpha = color.alpha * a)
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rectangle -> drawRect(fill)
                is Outline.Rounded -> drawPath(Path().apply { addRoundRect(outline.roundRect) }, fill)
                is Outline.Generic -> drawPath(outline.path, fill)
            }
        }
        drawContent()
    }
}

/**
 * Удобный хелпер: highlight для строки списка на текущей теме.
 */
fun iosRowHighlight(isDark: Boolean, shape: Shape): IosHighlightIndication =
    IosHighlightIndication(color = IosDesign.rowHighlight(isDark), shape = shape)
