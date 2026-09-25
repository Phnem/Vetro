package com.example.myapplication

import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.BrandLightGray
import com.example.myapplication.ui.shared.theme.BrandGray
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.sync.ExternalListService
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.phnem.vetro.R
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private val ConnectedGreen = BrandOrangeBright
private val LogoutRed = Color(0xFFE5382B)
private val AutoAmber = Color(0xFFD08A4A)

/** Действие внутри карточки сервиса — заменяет прежнее попап-меню. */
enum class SyncCardAction { PULL, PUSH, SYNC }

/**
 * Лист «Синхронизация»: компактная строка-шапка (мини-аватар · My Vetro · Done · AUTO)
 * + стопка горизонтальных «банковских» карточек сервисов. Карточки уходят строго вверх,
 * а строки Pull/Push/Sync внутри карточки играют роль прежнего попап-меню.
 */
@Composable
internal fun NottifSyncServiceGrid(
    isDark: Boolean,
    strings: UiStrings,
    ru: Boolean,
    badgeText: String,
    timeStr: String,
    datePart: String,
    statusWord: String,
    detailLine: String,
    syncingCloud: Boolean,
    isCheckingUpdates: Boolean,
    hasToken: Boolean,
    syncState: SyncState,
    onSyncNow: () -> Unit,
    onCheckUpdates: () -> Unit,
    onLogoutClick: () -> Unit,
    onServiceAction: (ExternalListService, SyncCardAction) -> Unit,
) {
    val connected = hasToken && syncState != SyncState.AUTH_REQUIRED &&
        syncState != SyncState.ERROR && syncState != SyncState.CONFLICT
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth()) {

        // ——— Шапка одной строкой ———
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(BrandDeepRed, BrandOrangeBright))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = strings.nottifAccountTitle,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                letterSpacing = (-0.3).sp,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(10.dp))
            // Зелёная пилюля-статус (Done / идёт синхронизация) — тап запускает синхронизацию.
            StatusDonePill(
                connected = connected,
                syncing = syncingCloud,
                doneText = if (ru) "Готово" else "Done",
                onClick = onSyncNow,
            )
            Spacer(Modifier.width(8.dp))
            // Жёлтый чипс режима (AUTO) — прямоугольный, как бейдж «Auto».
            AutoBadge(text = badgeText)
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        // ——— Стопка карточек сервисов ———
        SyncServiceCardStack(
            models = rememberSyncServiceModels(strings),
            copy = syncCardCopy(ru),
            onAction = onServiceAction,
        )

        // ——— Подсказка про свайп ———
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (ru) "Свайп вниз — следующая карта" else "Swipe down for the next card",
            fontFamily = SnProFamily,
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // ——— Выход (компактная строка) ———
        if (hasToken) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLogoutClick,
                    )
                    .defaultMinSize(minHeight = 40.dp)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ExitToApp, null, tint = LogoutRed, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (ru) "Выйти из аккаунта" else "Sign out",
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = LogoutRed,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

// ==========================================
// Шапка: пилюли статуса
// ==========================================

@Composable
private fun StatusDonePill(
    connected: Boolean,
    syncing: Boolean,
    doneText: String,
    onClick: () -> Unit,
) {
    val bg = ConnectedGreen.copy(alpha = 0.16f)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !syncing,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (syncing) {
            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 2.dp, color = ConnectedGreen)
        } else {
            Icon(Icons.Rounded.Check, null, tint = ConnectedGreen, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = doneText,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = ConnectedGreen,
        )
    }
}

/** Прямоугольный жёлтый бейдж режима — референс «Auto» из чата. */
@Composable
private fun AutoBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AutoAmber.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            color = AutoAmber,
        )
    }
}

// ==========================================
// Модель карточки сервиса
// ==========================================

private data class SyncServiceCardModel(
    val service: ExternalListService,
    val name: String,
    @DrawableRes val iconRes: Int,
    /** Тинт бренд-иконки в шапке; null → оставить родной цвет дровбла. */
    val brandTint: Color?,
    val bg: Color,
    val rowBg: Color,
    val iconHolderBg: Color,
    val onCard: Color,
    val onCardMuted: Color,
    val chevron: Color,
    val siteUrl: String,
)

/** Тексты Pull/Push/Sync: заголовки как в референсе (англ.), подписи локализованы. */
private data class SyncCardCopy(
    val pullTitle: String,
    val pushTitle: String,
    val syncTitle: String,
    val pullSub: String,
    val pushSub: String,
    val syncSub: String,
)

private fun syncCardCopy(ru: Boolean) = SyncCardCopy(
    pullTitle = "Pull",
    pushTitle = "Push",
    syncTitle = "Sync",
    pullSub = if (ru) "импорт вашей коллекции с сайта" else "for importing your collection from the site",
    pushSub = if (ru) "экспорт вашей коллекции на сайт" else "for exporting your collection to the site",
    syncSub = if (ru) "синхронизация с сайтом" else "for synchronization with the site",
)

@Composable
private fun rememberSyncServiceModels(strings: UiStrings): List<SyncServiceCardModel> = remember(strings) {
    listOf(
        // Shikimori — всегда светлая карта (бренд-цвет, не зависит от темы приложения).
        SyncServiceCardModel(
            service = ExternalListService.SHIKIMORI,
            name = strings.nottifServiceShikimori,
            iconRes = R.drawable.ic_shikimori,
            brandTint = Color(0xFF111111),
            bg = Color(0xFFEDEDED),
            rowBg = Color.White,
            iconHolderBg = Color.White,
            onCard = Color(0xFF111111),
            onCardMuted = BrandGray,
            chevron = BrandLightGray,
            siteUrl = "https://shikimori.io/",
        ),
        // AniList — почти чёрная карта (темнее фона листа, чтобы не сливалась),
        // лого остаётся фирменно-синим.
        SyncServiceCardModel(
            service = ExternalListService.ANILIST,
            name = strings.nottifServiceAnilist,
            iconRes = R.drawable.ic_anilist,
            brandTint = null,
            bg = Color(0xFF0A0A0A),
            rowBg = Color.White.copy(alpha = 0.08f),
            iconHolderBg = Color.White.copy(alpha = 0.12f),
            onCard = Color.White,
            onCardMuted = Color.White.copy(alpha = 0.55f),
            chevron = Color.White.copy(alpha = 0.4f),
            siteUrl = "https://anilist.co/",
        ),
        // MAL — фирменная оранжевая карта (брендовый градиентный тон Vetro).
        SyncServiceCardModel(
            service = ExternalListService.MYANIMELIST,
            name = strings.nottifServiceMal,
            iconRes = R.drawable.ic_myanimelist,
            brandTint = Color.White,
            bg = Color(0xFFB84102),
            rowBg = Color.White.copy(alpha = 0.12f),
            iconHolderBg = Color.White.copy(alpha = 0.16f),
            onCard = Color.White,
            onCardMuted = Color.White.copy(alpha = 0.7f),
            chevron = Color.White.copy(alpha = 0.55f),
            siteUrl = "https://myanimelist.net/",
        ),
    )
}

// ==========================================
// Стопка карточек (горизонтальные, уходят строго вверх — как карты в банк-приложениях)
// ==========================================

private val CARD_HEIGHT = 300.dp
private val CARD_PEEK = 42.dp
private const val CARD_SCALE_STEP = 0.05f
private const val STACK_VISIBLE = 3

private data class StackVisual(val translateY: Float, val scale: Float, val alpha: Float, val dim: Float)

@Composable
private fun SyncServiceCardStack(
    models: List<SyncServiceCardModel>,
    copy: SyncCardCopy,
    onAction: (ExternalListService, SyncCardAction) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var deck by remember(models) { mutableStateOf(models) }
    val offsetY = remember(models) { Animatable(0f) }
    var isFlyingOut by remember { mutableStateOf(false) }

    val upThresholdPx = with(density) { 90.dp.toPx() }
    val downThresholdPx = with(density) { 60.dp.toPx() }
    val peekPx = with(density) { CARD_PEEK.toPx() }
    val flyDistancePx = with(density) { (CARD_HEIGHT + CARD_PEEK * (STACK_VISIBLE)).toPx() }

    fun settleBack() {
        scope.launch { offsetY.animateTo(0f, animationSpec = MotionTokens.menuPop()) }
    }

    /**
     * Пролистнуть вперёд на [steps] позиций с анимацией (верхняя карта улетает вверх,
     * следующие подтягиваются за счёт progress). steps=1 — свайп/следующая,
     * steps>1 — тап по более глубокой карте (та же анимация, без резкого перескока).
     */
    fun commitTo(steps: Int) {
        if (isFlyingOut || deck.size <= 1) return
        scope.launch {
            performHaptic(view, Haptic.Light)
            isFlyingOut = true
            offsetY.animateTo(-flyDistancePx, animationSpec = tween(280))
            deck = deck.drop(steps) + deck.take(steps)
            offsetY.snapTo(0f)
            isFlyingOut = false
        }
    }

    fun commitPrev() {
        scope.launch {
            performHaptic(view, Haptic.Light)
            // последняя карта возвращается на фронт и «прилетает» сверху
            offsetY.snapTo(-flyDistancePx)
            deck = listOf(deck.last()) + deck.dropLast(1)
            offsetY.animateTo(0f, animationSpec = MotionTokens.sheetPresent())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT + CARD_PEEK * (STACK_VISIBLE - 1)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Положение карточек — в фазах слоя и отрисовки, не в композиции (см. SwipeableCardDeck).
        fun visualFor(index: Int): StackVisual {
            val offset = offsetY.value
            if (index == 0) {
                return StackVisual(
                    translateY = offset,
                    scale = 1f,
                    alpha = if (isFlyingOut) (1f - (-offset / 500f)).coerceIn(0f, 1f) else 1f,
                    dim = 0f,
                )
            }
            // Прогресс подтягивания следующей карты, пока верхнюю тянут вверх.
            val progress = (-offset / upThresholdPx).coerceIn(0f, 1f)
            val curY = -peekPx * index
            val nextY = -peekPx * (index - 1)
            val curScale = 1f - CARD_SCALE_STEP * index
            val nextScale = 1f - CARD_SCALE_STEP * (index - 1)
            return StackVisual(
                translateY = curY + (nextY - curY) * progress,
                scale = curScale + (nextScale - curScale) * progress,
                alpha = 1f,
                dim = (0.14f * index - 0.14f * progress).coerceIn(0f, 0.5f),
            )
        }

        deck.take(STACK_VISIBLE).withIndex().reversed().forEach { (index, model) ->
            val dragModifier = if (index == 0) {
                Modifier.pointerInput(deck) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            when {
                                offsetY.value < -upThresholdPx && deck.size > 1 -> commitTo(1)
                                offsetY.value > downThresholdPx && deck.size > 1 -> commitPrev()
                                else -> settleBack()
                            }
                        },
                        onDragCancel = { settleBack() },
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            val cur = offsetY.value
                            // вниз (undo) — с демпфированием, вверх — свободно
                            val delta = if (cur + dragAmount > 0f) dragAmount * 0.5f else dragAmount
                            offsetY.snapTo(cur + delta)
                        }
                    }
                }
            } else {
                // тап по «пику» задней карты — вывести её на фронт ТОЙ ЖЕ анимацией
                // пролистывания (не резкий перескок): пролистываем вперёд на index позиций.
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { commitTo(index) }
            }

            key(model.service) {
                Box(
                    modifier = Modifier
                        .zIndex((STACK_VISIBLE - index).toFloat())
                        .graphicsLayer {
                            val visual = visualFor(index)
                            translationY = visual.translateY
                            scaleX = visual.scale
                            scaleY = visual.scale
                            alpha = visual.alpha
                        }
                        .then(dragModifier),
                ) {
                    SyncServiceCard(
                        model = model,
                        copy = copy,
                        dim = { visualFor(index).dim },
                        interactive = index == 0,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncServiceCard(
    model: SyncServiceCardModel,
    copy: SyncCardCopy,
    /** Читается только при отрисовке — драг стопки не пересобирает карточку. */
    dim: () -> Float,
    interactive: Boolean,
    onAction: (ExternalListService, SyncCardAction) -> Unit,
) {
    val view = LocalView.current
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(shape)
            .background(model.bg)
            // Затемнение задних карточек в стопке — внутри клипа карточки.
            .drawWithContent {
                drawContent()
                val alpha = dim()
                if (alpha > 0f) drawRect(Color.Black, alpha = alpha)
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // —— Шапка карточки ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(model.iconHolderBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = model.iconRes),
                        contentDescription = model.name,
                        tint = model.brandTint ?: Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = model.name,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = (-0.3).sp,
                    color = model.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                // Кнопка «перейти на сайт» (нет на референсах — добавлено по ТЗ).
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(model.iconHolderBg)
                        .clickable(enabled = interactive) {
                            performHaptic(view, Haptic.Light)
                            uriHandler.openUri(model.siteUrl)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.ArrowOutward,
                        contentDescription = "Open site",
                        tint = model.onCard,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // —— Строки-действия (заменяют попап) ——
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SyncActionRow(
                    model, Icons.Rounded.Download, copy.pullTitle, copy.pullSub, interactive,
                ) { onAction(model.service, SyncCardAction.PULL) }
                SyncActionRow(
                    model, Icons.Rounded.Upload, copy.pushTitle, copy.pushSub, interactive,
                ) { onAction(model.service, SyncCardAction.PUSH) }
                SyncActionRow(
                    model, Icons.Rounded.Sync, copy.syncTitle, copy.syncSub, interactive,
                ) { onAction(model.service, SyncCardAction.SYNC) }
            }
        }

    }
}

@Composable
private fun SyncActionRow(
    model: SyncServiceCardModel,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    interactive: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val rowShape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(model.rowBg)
            .clickable(enabled = interactive) {
                performHaptic(view, Haptic.Light)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(model.iconHolderBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = model.onCard, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = model.onCard,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                color = model.onCardMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = model.chevron,
            modifier = Modifier.size(22.dp),
        )
    }
}
