package com.example.myapplication.ui.shared.components

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.domain.enrichment.weblinks.ApprovedSite
import com.example.myapplication.domain.enrichment.weblinks.ResolvedWebLink
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.WebLinkSites
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.phnem.vetro.R
import kotlin.math.roundToInt

/** Ключ сайта → круглая иконка. AniLibria (.top/.tv) — одна иконка на оба источника. */
@DrawableRes
fun webLinkIconRes(siteKey: String): Int? = when (siteKey) {
    WebLinkSites.ANILIBRIA_TOP, WebLinkSites.ANILIBRIA_TV -> R.drawable.ic_web_anilibria
    WebLinkSites.ANIMEVOST -> R.drawable.ic_web_animevost
    WebLinkSites.JUTSU -> R.drawable.ic_web_jutsu
    WebLinkSites.YUMMYANIME_TV -> R.drawable.ic_web_yummyanime_tv
    WebLinkSites.HIANIME -> R.drawable.ic_web_hianime
    WebLinkSites.JUSTWATCH -> R.drawable.ic_web_justwatch
    WebLinkSites.NETFLIX -> R.drawable.ic_web_netflix
    WebLinkSites.CRUNCHYROLL -> R.drawable.ic_web_crunchyroll
    else -> null
}

/** Стабильный порядок по каталогу [ApprovedSite]; неизвестные — в конец. */
private fun List<ResolvedWebLink>.orderedForDisplay(): List<ResolvedWebLink> =
    filter { webLinkIconRes(it.siteKey) != null }
        .distinctBy { it.siteKey }
        .sortedBy { ApprovedSite.of(it.siteKey)?.ordinal ?: Int.MAX_VALUE }

/**
 * Стопка круглых мини-чипсов найденных сайтов. АДАПТИВНАЯ по ширине: сама считает, сколько чипов
 * влезает в доступное место (BoxWithConstraints), остальное сворачивает в «+N» — никогда не
 * выдавливает соседей. В стопке один чип на ИКОНКУ (оба AniLibria — один чип), в меню — по строке
 * на источник. Тап → [WebLinkPopupMenu] с указателем на стопку и скримом.
 */
@Composable
fun WebLinkChipStack(
    links: List<ResolvedWebLink>,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    chipSize: Dp = 26.dp,
    maxVisible: Int = 4,
    ringColor: Color = MaterialTheme.colorScheme.surface,
) {
    val ordered = remember(links) { links.orderedForDisplay() }
    if (ordered.isEmpty()) return
    val stackIcons = remember(ordered) { ordered.mapNotNull { webLinkIconRes(it.siteKey) }.distinct() }

    BoxWithConstraints(modifier) {
        val overlap = chipSize * 0.42f
        val step = chipSize - overlap

        // Сколько «слотов» (чип/бейдж) физически помещается в доступную ширину.
        val fitSlots = if (maxWidth == Dp.Infinity) {
            Int.MAX_VALUE
        } else {
            (((maxWidth - chipSize) / step).toInt() + 1).coerceAtLeast(0)
        }
        var visibleCount = minOf(stackIcons.size, maxVisible)
        while (visibleCount > 0) {
            val slotsNeeded = visibleCount + if (stackIcons.size > visibleCount) 1 else 0
            if (slotsNeeded <= fitSlots) break
            visibleCount--
        }
        if (visibleCount <= 0) return@BoxWithConstraints

        val visible = stackIcons.take(visibleCount)
        val extra = stackIcons.size - visible.size
        val slots = visible.size + if (extra > 0) 1 else 0
        val stackWidth = chipSize + step * (slots - 1).coerceAtLeast(0)

        val menuState = remember { MutableTransitionState(false) }
        var anchorBounds by remember { mutableStateOf<Rect?>(null) }

        Box(
            modifier = Modifier
                .width(stackWidth)
                .height(chipSize)
                .onGloballyPositioned { anchorBounds = it.boundsInWindow() }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { menuState.targetState = true },
        ) {
            visible.forEachIndexed { index, res ->
                Image(
                    painter = painterResource(res),
                    contentDescription = null,
                    modifier = Modifier
                        .offset(x = step * index)
                        .size(chipSize)
                        .zIndex(index.toFloat())
                        .clip(CircleShape)
                        .border(1.5.dp, ringColor, CircleShape),
                )
            }
            if (extra > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = step * visible.size)
                        .size(chipSize)
                        .zIndex(visible.size.toFloat())
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.5.dp, ringColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+$extra",
                        fontFamily = SnProFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Popup живёт, пока идёт анимация появления/исчезновения (иначе exit не доиграет).
        if (menuState.targetState || menuState.currentState || !menuState.isIdle) {
            anchorBounds?.let { anchor ->
                WebLinkPopupMenu(
                    visibleState = menuState,
                    links = ordered,
                    anchor = anchor,
                    onDismiss = { menuState.targetState = false },
                )
            }
        }
    }
}

/**
 * Всплывающее меню по референсу: отдельные капсулы (иконка + имя + шеврон) без общей подложки,
 * треугольный «хвостик» указывает на стопку-якорь, под меню — затемняющий скрим на весь экран
 * (паттерн iOS-шитов приложения). Появление — пружинное «вытекание» из точки указателя
 * ([MotionTokens.menuPop]), закрытие — обратное «всасывание». Позиция считается заранее из
 * координат якоря (boundsInWindow) и известных размеров меню — вниз или вверх по свободному месту.
 */
@Composable
private fun WebLinkPopupMenu(
    visibleState: MutableTransitionState<Boolean>,
    links: List<ResolvedWebLink>,
    anchor: Rect,
    onDismiss: () -> Unit,
) {
    val inDark = isAppInDarkTheme()
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

    // Все размеры известны заранее → позиция и transformOrigin точны с первого кадра.
    val menuWidthPx = with(density) { MENU_WIDTH.toPx() }
    val rowPx = with(density) { MENU_ROW_HEIGHT.toPx() }
    val gapPx = with(density) { MENU_GAP.toPx() }
    val arrowPx = with(density) { ARROW_HEIGHT.toPx() }
    val marginPx = with(density) { 12.dp.toPx() }
    val anchorGapPx = with(density) { 8.dp.toPx() }

    val menuHeightPx = links.size * rowPx + (links.size - 1).coerceAtLeast(0) * gapPx + arrowPx
    val anchorCenterX = anchor.center.x
    val menuX = (anchorCenterX - menuWidthPx / 2f)
        .coerceIn(marginPx, (view.width - menuWidthPx - marginPx).coerceAtLeast(marginPx))
    val menuBelow = anchor.bottom + anchorGapPx + menuHeightPx + marginPx <= view.height
    val menuY = if (menuBelow) {
        anchor.bottom + anchorGapPx
    } else {
        (anchor.top - anchorGapPx - menuHeightPx).coerceAtLeast(marginPx)
    }
    val arrowFraction = ((anchorCenterX - menuX) / menuWidthPx).coerceIn(0.08f, 0.92f)
    val origin = TransformOrigin(arrowFraction, if (menuBelow) 0f else 1f)

    val pillColor = if (inDark) Color(0xFF232329) else Color(0xFFF4F4F6)
    val pillRing = if (inDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f)
    val onPill = MaterialTheme.colorScheme.onSurface
    val chevron = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val scrimColor = Color.Black.copy(alpha = if (inDark) 0.55f else 0.35f)

    // Popup по умолчанию позиционируется ОТНОСИТЕЛЬНО ЯКОРЯ — прибиваем окно к (0,0) окна
    // приложения, чтобы вся математика в оконных координатах (boundsInWindow) была точной.
    val fullWindowProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset.Zero
        }
    }

    Popup(
        popupPositionProvider = fullWindowProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // Окно попапа начинается ниже статус-бара, а anchor посчитан в координатах окна приложения
        // (edge-to-edge, от верха экрана). Меряем оба окна на экране и переводим координаты.
        val popupView = LocalView.current
        var windowDelta by remember { mutableStateOf<IntOffset?>(null) }

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val app = IntArray(2).also { view.getLocationOnScreen(it) }
                    val popup = IntArray(2).also { popupView.getLocationOnScreen(it) }
                    windowDelta = IntOffset(app[0] - popup[0], app[1] - popup[1])
                },
        ) {
            // Скрим: отделяет меню от «каши» контента (fade вместе с меню).
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(MotionTokens.menuPop()),
                exit = fadeOut(MotionTokens.sheetDismissForced()),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(scrimColor)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismiss() },
                )
            }

            val delta = windowDelta ?: return@Box
            AnimatedVisibility(
                visibleState = visibleState,
                modifier = Modifier.offset {
                    IntOffset(menuX.roundToInt() + delta.x, menuY.roundToInt() + delta.y)
                },
                enter = scaleIn(MotionTokens.menuPop(), initialScale = 0.25f, transformOrigin = origin) +
                    fadeIn(MotionTokens.menuPop()),
                exit = scaleOut(MotionTokens.sheetDismissForced(), targetScale = 0.4f, transformOrigin = origin) +
                    fadeOut(MotionTokens.sheetDismissForced()),
            ) {
                Column(modifier = Modifier.width(MENU_WIDTH)) {
                    if (menuBelow) MenuArrow(pointUp = true, fraction = arrowFraction, color = pillColor)
                    Column(verticalArrangement = Arrangement.spacedBy(MENU_GAP)) {
                        links.forEach { link ->
                            val res = webLinkIconRes(link.siteKey) ?: return@forEach
                            val name = ApprovedSite.of(link.siteKey)?.displayName ?: link.siteKey
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(MENU_ROW_HEIGHT)
                                    .shadow(if (inDark) 0.dp else 6.dp, RoundedCornerShape(50))
                                    .clip(RoundedCornerShape(50))
                                    .background(pillColor)
                                    .border(1.dp, pillRing, RoundedCornerShape(50))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                        onDismiss()
                                    }
                                    .padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    painter = painterResource(res),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, pillRing, CircleShape),
                                )
                                Spacer(Modifier.width(13.dp))
                                Text(
                                    text = name,
                                    modifier = Modifier.weight(1f),
                                    fontFamily = SnProFamily,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = onPill,
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = chevron,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                    if (!menuBelow) MenuArrow(pointUp = false, fraction = arrowFraction, color = pillColor)
                }
            }
        }
    }
}

/** Треугольный «хвостик» меню (как у пузырька в комиксах), указывает на стопку-якорь. */
@Composable
private fun MenuArrow(pointUp: Boolean, fraction: Float, color: Color) {
    Canvas(
        modifier = Modifier
            .offset(x = MENU_WIDTH * fraction - ARROW_WIDTH / 2)
            .size(ARROW_WIDTH, ARROW_HEIGHT),
    ) {
        val path = Path().apply {
            if (pointUp) {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
            }
            close()
        }
        drawPath(path, color)
    }
}

private val MENU_WIDTH = 280.dp
private val MENU_ROW_HEIGHT = 56.dp
private val MENU_GAP = 6.dp
private val ARROW_WIDTH = 20.dp
private val ARROW_HEIGHT = 10.dp
