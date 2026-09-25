package com.example.myapplication.ui.workspace

import com.example.myapplication.ui.shared.PlacedCoordinates
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.home.CapsuleDockItem
import com.example.myapplication.ui.home.CapsuleGlassDock
import com.example.myapplication.ui.shared.LocalWorkspaceSearch
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.kyant.backdrop.Backdrop
import com.phnem.vetro.R

// ==========================================
// Док-селектор рабочей области: капсула матового стекла (CapsuleGlassDock) с подвижной
// подсветкой текущего раздела, гнездом меню ТТМ и кнопкой поиска.
// ==========================================

private val DOCK_HEIGHT = 74.dp
private val ICON_SIZE = 26.dp

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
    /** Координаты гнезда ТТМ: из него вырастает меню (см. [TtmMenu]). */
    menuAnchor: PlacedCoordinates? = null,
    /** Окно, открытое пунктом меню, вырастает из гнезда ТТМ и в него же схлопывается. */
    menuWindowMorph: MenuWindowMorph? = null,
) {
    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.35f else 1f,
        animationSpec = MotionTokens.standard(),
        label = "workspaceDockDim",
    )
    val hideProgress by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = MotionTokens.standard(),
        label = "workspaceDockHide",
    )
    val hideDistancePx = with(LocalDensity.current) { (DOCK_HEIGHT + 40.dp).toPx() }

    // Внизу рабочей области — СТРАНИЦЫ пейджера, а не действия: подсветка отмечает настоящий
    // текущий раздел, а не последний нажатый.
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
            anchor = menuAnchor,
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
                IntOffset(0, (hideDistancePx * hideProgress).roundToInt())
            }
            .graphicsLayer { alpha = dimAlpha * (1f - hideProgress) },
    )
}

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
