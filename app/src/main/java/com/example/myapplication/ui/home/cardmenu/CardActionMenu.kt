package com.example.myapplication.ui.home.cardmenu

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.example.myapplication.ui.home.AnimeCardBody
import com.example.myapplication.ui.home.AnimeCardState
import com.example.myapplication.ui.home.FavoriteCollectionCard
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.kyant.backdrop.Backdrop

// ==========================================
// Контекстное меню карточки (долгое удержание).
//
// Карточка поднимается над затемнённым списком, под ней всплывает ряд действий. Раскладка,
// анимации и модальность — в общем [ActionMenuOverlay]; здесь только набор кнопок карточки и
// копия самой карточки.
// ==========================================

/** Что именно выделено: данные карточки и её место на экране в момент удержания. */
@Immutable
data class CardMenuTarget(
    val state: AnimeCardState,
    val isFavorite: Boolean,
    /** Границы карточки в координатах корня окна. */
    val boundsInRoot: Rect,
    /** Portrait favorites keep their own appearance in the lifted preview. */
    val favoritePosition: Int? = null,
    val releaseYear: Int? = null,
)

@Composable
fun CardActionMenuOverlay(
    target: CardMenuTarget,
    /**
     * Показано ли меню.
     *
     * Отдельный признак, а не «есть цель — значит открыто»: уходить оверлей обязан так же, как
     * пришёл, а для этого он должен пережить собственное закрытие. Цель снимает уже [onClosed],
     * когда обратный ход доигран.
     */
    visible: Boolean,
    /** Живой бэкдроп страницы: кнопки действий сделаны из матового стекла над её содержимым. */
    backdrop: Backdrop,
    /** Нижняя занятая зона (док + нав-бар): ряд кнопок не должен под неё уезжать. */
    bottomInset: Dp,
    topInset: Dp,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isAppInDarkTheme()
    val neutralTint = if (isDark) Color.White else Color(0xFF1C1C1E)
    val actions = listOf(
        ActionMenuItem(
            icon = if (target.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            tint = OverlayThemeTokens.FavoriteGold,
            contentDescription = null,
            onClick = onToggleFavorite,
        ),
        ActionMenuItem(
            icon = Icons.Rounded.Delete,
            tint = Color(0xFFE5382B),
            contentDescription = null,
            onClick = onDelete,
        ),
        ActionMenuItem(
            icon = Icons.Filled.Edit,
            tint = neutralTint,
            contentDescription = null,
            onClick = onEdit,
        ),
        ActionMenuItem(
            icon = Icons.Outlined.Info,
            tint = neutralTint,
            contentDescription = null,
            onClick = onDetails,
        ),
    )

    ActionMenuOverlay(
        boundsInRoot = target.boundsInRoot,
        visible = visible,
        backdrop = backdrop,
        bottomInset = bottomInset,
        topInset = topInset,
        actions = actions,
        onDismiss = onDismiss,
        onClosed = onClosed,
        modifier = modifier,
    ) {
        val favoritePosition = target.favoritePosition
        if (favoritePosition != null) {
            FavoriteCollectionCard(
                state = target.state,
                releaseYear = target.releaseYear,
                position = favoritePosition,
                onClick = onDismiss,
                onLongClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AnimeCardBody(state = target.state)
        }
    }
}
