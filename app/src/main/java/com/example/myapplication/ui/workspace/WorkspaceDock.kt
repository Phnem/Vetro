package com.example.myapplication.ui.workspace

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.GlassPreset
import com.example.myapplication.ui.shared.adaptiveGlassBackdrop
import com.example.myapplication.ui.shared.rememberAdaptiveGlassEffects
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.home.CapsuleDockItem
import com.example.myapplication.ui.home.CapsuleGlassDock
import com.example.myapplication.ui.shared.LocalGlassCapsuleDock
import com.example.myapplication.ui.shared.LocalWorkspaceSearch
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.kyant.backdrop.Backdrop
import com.phnem.vetro.R

// ==========================================
// Док-селектор рабочей области (TICKET-11).
//
// Раскладка по референсу пользователя: неактивные разделы — круглые пузырьки с одной иконкой,
// активный — капсула «иконка + подпись» в строку. Прежний вариант (подпись ПОД иконкой у всех
// пяти пунктов, как в Telegram) читался плотно и шумно; здесь подпись остаётся ровно у одного
// пункта, а остальные держат ритм круглых пузырьков.
//
// Переключение — перетекание, а не смена картинки: форма у пункта одна и та же (CircleShape,
// круг и капсула — её крайние случаи), меняется только ширина, поэтому скругление плывёт само.
// Подпись всегда смонтирована и «выползает» из-под иконки за счёт клипа родителя — не
// AnimatedVisibility: размонтирование внутри потребителя бэкдропа лишний раз дёргает стекло.
// ==========================================

private val DOCK_HEIGHT = 74.dp
private val ICON_SIZE = 26.dp
/** Диаметр пузырька; он же высота капсулы. */
private val ITEM_SIZE = 56.dp
/**
 * Ширина иконочного бокса в РАСКРЫТОЙ капсуле. Меньше диаметра пузырька: пока бокс держал все
 * 56dp, иконка стояла в его центре и от подписи её отделяло полбокса пустоты.
 */
private val ICON_BOX_EXPANDED = 42.dp
/** Отступ от иконки до подписи и от подписи до правого края капсулы. */
private val LABEL_GAP = 2.dp
private val LABEL_END_PADDING = 16.dp
/** Потолок для подписи: на узком экране длинная локаль не должна выдавливать соседей. */
private val LABEL_MAX_WIDTH = 96.dp

/**
 * Сколько места страницы обязаны оставить снизу под доком (без нав-панели — её страницы
 * добавляют сами своими `navigationBarsPadding`). Высота дока + его отступ + воздух.
 */
val WorkspaceDockInset: Dp = DOCK_HEIGHT + 16.dp + 12.dp

@Composable
fun WorkspaceDock(
    /** Тот же приём, что у мини-дока Details: капсула сэмплит живой бэкдроп под собой. */
    backdrop: Backdrop,
    selected: WorkspacePage,
    language: AppLanguage,
    onSelect: (WorkspacePage) -> Unit,
    modifier: Modifier = Modifier,
    /** Карточка выделена контекстным меню: док гаснет вместе с фоном и не реагирует на тапы. */
    dimmed: Boolean = false,
    /** Открыта шторка или диалог: док уезжает вниз целиком, чтобы не спорить с ними за низ экрана. */
    hidden: Boolean = false,
    /** Раскрыто ли меню последнего гнезда — подсветка обязана стоять на нём, пока оно открыто. */
    menuOpen: Boolean = false,
    onOpenMenu: () -> Unit = {},
    /** Границы гнезда ТТМ: из них вырастает меню (см. [TtmMenu]). */
    onMenuBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** Окно, открытое пунктом меню, вырастает из гнезда ТТМ и в него же схлопывается. */
    menuWindowMorph: MenuWindowMorph? = null,
) {
    val isDark = isAppInDarkTheme()
    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.35f else 1f,
        animationSpec = MotionTokens.standard(),
        label = "workspaceDockDim",
    )
    val hideProgressForCapsule by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = MotionTokens.standard(),
        label = "workspaceDockHideCapsule",
    )
    val hideDistanceForCapsule = with(LocalDensity.current) { (DOCK_HEIGHT + 40.dp).toPx() }

    // Dev-тумблер «Док из матового стекла» действует и здесь: режим свайпа и новый вид дока —
    // не две альтернативы, а два независимых слоя одного эксперимента. Состав пунктов при этом
    // остаётся свой: внизу рабочей области это СТРАНИЦЫ пейджера, а не действия, и подсветка
    // отмечает настоящий текущий раздел, а не последний нажатый.
    if (LocalGlassCapsuleDock.current) {
        val search = LocalWorkspaceSearch.current
        val ru = language == AppLanguage.RU
        val menuLabel = if (ru) "Ещё" else "More"
        CapsuleGlassDock(
            backdrop = backdrop,
            items = WorkspacePage.Ordered.map { page ->
                val label = page.label(language)
                CapsuleDockItem(
                    contentDescription = label,
                    onClick = { if (!dimmed && !hidden) onSelect(page) },
                ) { tint ->
                    WorkspacePageIcon(page = page, tint = tint, contentDescription = label)
                }
            } + CapsuleDockItem(
                // Последнее гнездо — не раздел: оно раскрывает меню поверх текущей страницы.
                // Свайпом сюда не попасть, поэтому в WorkspacePage его и нет.
                contentDescription = menuLabel,
                onClick = { if (!dimmed && !hidden) onOpenMenu() },
                // Панели (статистика, синхронизация) открываются пунктами ЭТОГО меню, значит и
                // расти обязаны отсюда. Без этого точка выхода оставалась там, где её записал
                // верхний док, — панель выезжала из верха экрана в нижнюю шторку.
                isMorphOrigin = true,
                reportBounds = onMenuBounds,
            ) { tint ->
                MenuSlotIcon(morph = menuWindowMorph, tint = tint, contentDescription = menuLabel)
            },
            selectedIndex = if (menuOpen) WorkspacePage.PageCount else selected.index,
            // Кнопка поиска видна ВСЕГДА, а не только на коллекции: иначе капсула меняла бы
            // ширину при каждом свайпе, а вместе с ней прыгал бы шаг подсветки. Нажатие с
            // другого раздела сначала возвращает на коллекцию — искать всё равно можно только там.
            trailingButton = CapsuleDockItem(
                contentDescription = if (language == AppLanguage.RU) "Поиск" else "Search",
                onClick = {
                    if (dimmed || hidden) return@CapsuleDockItem
                    if (selected != WorkspacePage.HOME) onSelect(WorkspacePage.HOME)
                    search.toggle()
                },
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.dock_search_24),
                    contentDescription = if (language == AppLanguage.RU) "Поиск" else "Search",
                    tint = tint,
                    modifier = Modifier.size(ICON_SIZE),
                )
            },
            trailingActive = search.active,
            modifier = modifier
                .offset {
                    IntOffset(0, (hideDistanceForCapsule * hideProgressForCapsule).roundToInt())
                }
                .graphicsLayer { alpha = dimAlpha * (1f - hideProgressForCapsule) },
        )
        return
    }
    val glassEffects = rememberAdaptiveGlassEffects(GlassPreset.CompactNav)
    val dockShape = RoundedCornerShape(DOCK_HEIGHT / 2)
    val borderStroke = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)

    val hideProgress by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = MotionTokens.standard(),
        label = "workspaceDockHide",
    )
    val hideDistancePx = with(LocalDensity.current) { (DOCK_HEIGHT + 40.dp).toPx() }

    // Ширина подписи меряется, а не подбирается: капсула обязана открыться ровно под свой текст,
    // иначе «Настройки» упирается в край и обрезается, а «Add» оставляет пустую половину.
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelWidths = remember(language, density, textMeasurer) {
        WorkspacePage.Ordered.associateWith { page ->
            val measured = textMeasurer.measure(page.label(language), LabelTextStyle).size.width
            with(density) { measured.toDp() }.coerceAtMost(LABEL_MAX_WIDTH)
        }
    }

    Row(
        modifier = modifier
            // Уезжает через offset, а НЕ через AnimatedVisibility и не через translationY в
            // graphicsLayer: размонтирование потребителя обнуляет запись бэкдропа, а сдвиг
            // слоем роняет стекло в плоскую заливку (известные грабли этой кодовой базы).
            .offset { IntOffset(0, (hideDistancePx * hideProgress).roundToInt()) }
            .fillMaxWidth(0.94f)
            .height(DOCK_HEIGHT)
            .graphicsLayer { alpha = dimAlpha * (1f - hideProgress) }
            .clip(dockShape)
            .adaptiveGlassBackdrop(backdrop = backdrop, shape = dockShape, effects = glassEffects)
            .border(0.5.dp, borderStroke, dockShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Ширина активного пункта плавает, поэтому раскладка не может стоять на фиксированных
        // долях: SpaceEvenly сам поджимает зазоры, пока капсула раскрывается.
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        WorkspacePage.Ordered.forEach { page ->
            WorkspaceDockItem(
                page = page,
                label = page.label(language),
                labelWidth = labelWidths.getValue(page),
                selected = page == selected,
                isDark = isDark,
                enabled = !dimmed && !hidden,
                onClick = { onSelect(page) },
            )
        }
    }
}

@Composable
private fun WorkspaceDockItem(
    page: WorkspacePage,
    label: String,
    labelWidth: Dp,
    selected: Boolean,
    isDark: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Одна пружина на всё перетекание — та же, что у перелистывания страниц (`goTo` в
    // WorkspaceScreen): капсула и пейджер должны ехать одним движением. Ширины считаются из
    // общей доли, иначе бокс иконки и капсула разъезжались бы каждый своим темпом.
    val expansion by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MotionTokens.sheetPresent(),
        label = "workspaceDockExpansion",
    )
    val expandedWidth = ICON_BOX_EXPANDED + LABEL_GAP + labelWidth + LABEL_END_PADDING
    val width = lerp(ITEM_SIZE, expandedWidth, expansion)
    val iconBoxWidth = lerp(ITEM_SIZE, ICON_BOX_EXPANDED, expansion)
    val labelAlpha = expansion
    val bubbleBg by animateColorAsState(
        targetValue = when {
            // Активный — плотная белая капсула с фирменным оранжевым содержимым: тот же приём,
            // что на референсе (белая пилюля + акцентная иконка), только в палитре проекта.
            selected -> Color.White
            isDark -> Color.White.copy(alpha = 0.10f)
            else -> Color.Black.copy(alpha = 0.05f)
        },
        animationSpec = MotionTokens.sheetPresent(),
        label = "workspaceDockBubbleBg",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            selected -> BrandOrange
            isDark -> Color.White.copy(alpha = 0.62f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        },
        animationSpec = MotionTokens.sheetPresent(),
        label = "workspaceDockContentColor",
    )

    Box(
        modifier = Modifier
            .width(width)
            .height(ITEM_SIZE)
            // Одна форма на оба состояния: у круга и у капсулы скругление одинаковое (половина
            // высоты), поэтому смены shape на переходе нет — плывёт только ширина.
            .clip(CircleShape)
            .background(bubbleBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            // unbounded: строка меряется по своему полному размеру и уходит под клип родителя,
            // поэтому подпись выползает из-под иконки, а не сжимается в многоточие.
            modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Бокс иконки сжимается вместе с раскрытием: у пузырька иконка ровно по центру,
            // у капсулы — прижата к левому краю, и подпись идёт сразу за ней.
            Box(
                modifier = Modifier.width(iconBoxWidth).height(ITEM_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                WorkspacePageIcon(page = page, tint = contentColor, contentDescription = label)
            }
            Text(
                text = label,
                style = LabelTextStyle,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = LABEL_GAP)
                    .width(labelWidth)
                    .graphicsLayer { alpha = labelAlpha },
            )
        }
    }
}

/** Стиль подписи вынесен: им же меряется ширина капсулы, значения обязаны совпадать. */
private val LabelTextStyle = TextStyle(
    fontFamily = SnProFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
)

@Composable
private fun WorkspacePageIcon(page: WorkspacePage, tint: Color, contentDescription: String) {
    val iconModifier = Modifier.size(ICON_SIZE)
    // Набор иконок дока — один и тот же файл на раздел, а не «что-то похожее из material»:
    // так все четыре гнезда нарисованы в одном стиле и одной толщиной штриха.
    val painter = when (page) {
        WorkspacePage.HOME -> R.drawable.dock_home_24
        WorkspacePage.SETTINGS -> R.drawable.dock_settings_24
        WorkspacePage.BOOKS -> R.drawable.dock_books_24
    }
    Icon(
        painter = painterResource(painter),
        contentDescription = contentDescription,
        tint = tint,
        modifier = iconModifier,
    )
}

/**
 * Подписи разделов. Живут здесь, а не в `UiStrings`: там 252 поля из 254 допустимых
 * (за пределом RELEASE падает с VerifyError в clinit), а весь док пока под dev-флагом.
 */
private fun WorkspacePage.label(language: AppLanguage): String {
    val ru = language == AppLanguage.RU
    return when (this) {
        WorkspacePage.BOOKS -> if (ru) "Книги" else "Books"
        WorkspacePage.HOME -> if (ru) "Главная" else "Home"
        WorkspacePage.SETTINGS -> if (ru) "Настройки" else "Settings"
    }
}

/**
 * Связь гнезда ТТМ с полноэкранным окном, которое открыто пунктом меню.
 *
 * Сами пункты исчезают вместе с меню, поэтому источник окна — гнездо «Ещё», из которого меню
 * выросло: окно выходит из него и в него же схлопывается (закон 1 спеки движения). [key] —
 * ключ shared-bounds того окна, что открыто последним (`inspect_container` / `fab_container`,
 * их же ждут корни экранов); `null` — из меню ещё ничего не открывали.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
class MenuWindowMorph(
    val sharedTransitionScope: SharedTransitionScope,
    val animatedVisibilityScope: AnimatedVisibilityScope,
    val key: String?,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MenuSlotIcon(morph: MenuWindowMorph?, tint: Color, contentDescription: String) {
    val icon = @Composable {
        Icon(
            painter = painterResource(R.drawable.dock_menu_24),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ICON_SIZE),
        )
    }
    if (morph == null) {
        icon()
        return
    }
    with(morph.sharedTransitionScope) {
        Box(
            modifier = Modifier
                .size(MENU_SLOT_MORPH_SIZE)
                .sharedBounds(
                    // Ключ меняется значением, узел остаётся тем же — структура дока не
                    // перестраивается, когда меню открывает то одно окно, то другое.
                    sharedContentState = rememberSharedContentState(morph.key ?: MENU_SLOT_IDLE_KEY),
                    animatedVisibilityScope = morph.animatedVisibilityScope,
                    // Иконка — содержимое гнезда: при открытии гаснет первой, при закрытии
                    // проявляется, когда окно уже почти вернулось.
                    enter = fadeIn(
                        tween(
                            durationMillis = MotionTokens.EaseEnterMillis,
                            delayMillis = MENU_SLOT_ICON_RETURN_DELAY_MS,
                            easing = MotionTokens.EaseEnter,
                        ),
                    ),
                    exit = fadeOut(tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit)),
                    // Закрытие: гнездо — цель, и пружина берётся с его стороны (закон 5).
                    boundsTransform = { _, _ -> MotionTokens.largeSurfaceExitBounds },
                    // Внутри гнезда только иконка — её дешевле перемерить, чем растянуть масштабом.
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

/** Ключ гнезда, пока из меню не открывали окон: пары у него нет, морф не срабатывает. */
private const val MENU_SLOT_IDLE_KEY = "ttm_menu_slot"

/** Размер морф-области гнезда — как у иконок старого дока, из которых морфили те же окна. */
private val MENU_SLOT_MORPH_SIZE = 36.dp

/** Иконка возвращается в гнездо, когда окно на пружине закрытия прошло бо́льшую часть пути. */
private const val MENU_SLOT_ICON_RETURN_DELAY_MS = 120
