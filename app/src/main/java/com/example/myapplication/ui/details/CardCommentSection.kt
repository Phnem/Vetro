package com.example.myapplication.ui.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Секция «Мой комментарий» на экране Details: короткий комментарий пользователя (тот же, что стоит
 * пузырём на карточке «Что я смотрю»). Тап по тексту — поправить, корзина — удалить.
 *
 * Удаление идёт в три шага. Корзина только просит подтверждения ([onAskDelete]); после «Удалить» вызывающий
 * ставит [leaving], секция наливается красным и улетает за край экрана, и только потом зовёт [onLeft]:
 * вызывающий стирает комментарий, а освободившееся место схлопывается пружиной.
 *
 * @param comment сохранённый комментарий; пусто — секции нет вовсе.
 * @param leaving подтверждённое удаление: запускает вылет.
 */
@Composable
internal fun CardCommentSection(
    comment: String,
    ru: Boolean,
    leaving: Boolean,
    onEdit: () -> Unit,
    onAskDelete: () -> Unit,
    onLeft: () -> Unit,
) {
    // Во время ухода комментарий в хранилище уже пуст, а секция ещё должна показывать последний текст.
    val shown = remember { mutableStateOf(comment) }
    LaunchedEffect(comment) { if (comment.isNotEmpty()) shown.value = comment }

    AnimatedVisibility(
        visible = comment.isNotEmpty(),
        enter = expandVertically(MotionTokens.springObject()) + fadeIn(MotionTokens.springObject()),
        exit = shrinkVertically(MotionTokens.springSurface()) + fadeOut(MotionTokens.springSurface()),
    ) {
        CommentBody(shown.value, ru, leaving, onEdit, onAskDelete, onLeft)
    }
}

@Composable
private fun CommentBody(
    text: String,
    ru: Boolean,
    leaving: Boolean,
    onEdit: () -> Unit,
    onAskDelete: () -> Unit,
    onLeft: () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val flyDistancePx = LocalContext.current.resources.displayMetrics.widthPixels * 1.25f
    val fly = remember { Animatable(0f) }
    val red = remember { Animatable(0f) }

    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        if (reduced) {
            onLeft()
            return@LaunchedEffect
        }
        coroutineScope {
            // Сначала секция наливается красным, через мгновение отрывается и улетает.
            launch { red.animateTo(1f, MotionTokens.springSnappy()) }
            launch {
                delay(FLY_DELAY_MILLIS)
                fly.animateTo(1f, MotionTokens.notificationFling())
            }
        }
        onLeft()
    }

    val onBg = MaterialTheme.colorScheme.onBackground
    val surface = onBg.copy(alpha = 0.06f)
    Column {
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier.graphicsLayer {
                translationX = fly.value * flyDistancePx
                rotationZ = fly.value * FLY_TILT_DEGREES
                alpha = (1f - fly.value * 0.4f).coerceIn(0f, 1f)
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SectionHeader(text = if (ru) "Мой комментарий" else "My comment", color = onBg)
                }
                val trashTint = lerp(onBg.copy(alpha = 0.7f), BrandDeepRed, red.value)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(lerp(surface, BrandDeepRed.copy(alpha = 0.18f), red.value))
                        .clickable(
                            enabled = !leaving,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = if (ru) "Удалить комментарий" else "Delete comment",
                            onClick = onAskDelete,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = trashTint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(22.dp))
                    .background(lerp(surface, BrandDeepRed.copy(alpha = 0.20f), red.value))
                    .border(1.dp, BrandDeepRed.copy(alpha = 0.65f * red.value), RoundedCornerShape(22.dp))
                    .clickable(
                        enabled = !leaving,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = if (ru) "Изменить комментарий" else "Edit comment",
                        onClick = onEdit,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(lerp(BrandOrange, BrandDeepRed, red.value)),
                )
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontStyle = FontStyle.Italic,
                    color = lerp(onBg.copy(alpha = 0.86f), BrandDeepRed, red.value * 0.8f),
                )
            }
        }
    }
}

/**
 * Подтверждение удаления комментария: шторка с красной кнопкой. Сам комментарий не стирает: после
 * [onConfirm] вызывающий запускает вылет секции, а стирание идёт в конце вылета.
 */
@Composable
internal fun DeleteCardCommentSheet(
    visible: Boolean,
    comment: String,
    ru: Boolean,
    isDark: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    if (!state.currentState && !state.targetState) return
    val view = LocalView.current

    MotionBottomSheet(visibleState = state, onDismiss = onDismiss, isDark = isDark, panelModifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                if (ru) "Удалить комментарий?" else "Delete the comment?",
                fontFamily = SnProFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (ru) "«$comment»\nОн пропадёт и с экрана тайтла, и с карточки для друзей."
                else "“$comment”\nIt will disappear from the title screen and from the share card.",
                fontFamily = SnProFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ru) "Отмена" else "Cancel",
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp),
                )
                Text(
                    if (ru) "Удалить" else "Delete",
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(BrandDeepRed)
                        .clickable {
                            performHaptic(view, Haptic.Success)
                            onConfirm()
                        }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

/** Пауза между «налилась красным» и «оторвалась»: красный успевает прочитаться до вылета. */
private const val FLY_DELAY_MILLIS = 140L

/** Наклон улетающей секции, градусы. */
private const val FLY_TILT_DEGREES = 9f
