package com.example.myapplication.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.ln
import com.example.myapplication.ui.shared.theme.MotionTokens

// ==========================================
// Inertial collision (Phase-Aware, Draw-only, Zero Jank)
// ==========================================

/**
 * Вектор инерции при столкновении.
 */
enum class CollisionDirection {
    BottomUp, // Столкновение с верхней границей: элементы летят вверх (-Y)
    TopDown   // Столкновение с нижней границей/дропдауном: элементы летят вниз (+Y)
}

/**
 * Single Source of Truth для физики столкновения.
 * Изолирует бизнес-логику анимации от UI. Один Animatable на весь список — без аллокаций на каждый item.
 */
@Stable
class InertialCollisionState {
    val force = Animatable(0f)

    suspend fun triggerCollision(
        impactForce: Float = 30f,
        stiffness: Float = 350f,
        dampingRatio: Float = 0.55f
    ) {
        force.snapTo(impactForce)
        force.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = dampingRatio,
                stiffness = stiffness
            )
        )
    }
}

@Composable
fun rememberInertialCollisionState(): InertialCollisionState {
    return remember { InertialCollisionState() }
}

/**
 * Применяет эффект деформации строго на GPU (Draw фаза). Чтение state.force.value в graphicsLayer
 * инвалидирует только Draw — без рекомпозиции и ре-лейаута.
 */
fun Modifier.inertialCollision(
    state: InertialCollisionState,
    index: Int,
    baseMultiplier: Float = 3.5f,
    direction: CollisionDirection = CollisionDirection.BottomUp
): Modifier = this.then(
    Modifier.graphicsLayer {
        // Натуральный логарифм сглаживает влияние индекса — нижние элементы не «улетают» слишком далеко.
        val safeIndexScale = ln((index + 1).toFloat())
        val vectorSign = if (direction == CollisionDirection.BottomUp) -1f else 1f
        val totalDisplacement = state.force.value * safeIndexScale * baseMultiplier * vectorSign

        translationY = totalDisplacement

        // Дополнительный приём: лёгкое сжатие по оси Y (можно включить при желании).
        // val scaleCompress = 1f - (state.force.value * 0.001f * safeIndexScale).coerceIn(0f, 0.1f)
        // scaleY = scaleCompress
    }
)

// ==========================================
// iOS-like rubber-band overscroll
// ==========================================

/**
 * Современный модификатор для iOS-like нажатий.
 * @param scaleDown Масштаб элемента при удержании (по умолчанию 0.95f)
 * @param enabled Активна ли кнопка
 * @param onClick Действие при клике
 */
fun Modifier.fluidClickable(
    scaleDown: Float = 0.95f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    /** Долгое удержание. null — обычный clickable, поведение вызывающих не меняется. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Нажатие кнопки/строки (§7): токен motion.press (987/0.60).
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = MotionTokens.press(),
        label = "fluidClickScale"
    )

    val base = this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    if (onLongClick == null) {
        base.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick
        )
    } else {
        base.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick
        )
    }
}

// ==========================================
// Gradient highlight border («блик»)
// ==========================================

/**
 * Обводка в виде вертикального градиента (блик): сверху ярче, снизу мягче.
 * Используется для оранжевых кнопок (Details, OK, Done).
 */
fun Modifier.gradientHighlightBorder(cornerRadiusDp: Dp, isDark: Boolean): Modifier =
    drawWithContent {
        drawContent()
        val strokeWidth = 1.dp.toPx()
        val cornerRadius = (cornerRadiusDp.toPx() - strokeWidth / 2f).coerceAtLeast(0f)
        val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
        val rectSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val gradientBrush = if (isDark) Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.5f),
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.12f)
            )
        ) else Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.75f),
                Color.White.copy(alpha = 0.4f),
                Color.White.copy(alpha = 0.2f)
            )
        )
        drawRoundRect(
            brush = gradientBrush,
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = CornerRadius(cornerRadius),
            style = Stroke(width = strokeWidth)
        )
    }
