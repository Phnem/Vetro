package com.example.myapplication.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreHoriz
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.myapplication.ui.shared.LocalModernUi
import com.example.myapplication.ui.shared.glassControl
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val PanelShape = RoundedCornerShape(22.dp)

/**
 * Пузырь «⋯» в углу Details: то, что нужно, когда алгоритм ошибся или тайтл досмотрен вне
 * приложения. Три действия: отметить просмотренным, сменить обложку и признать источник неверным
 * (предлагает варианты тайтла, так уходит чужой «Psyren»). Два последних открывают шторку выбора,
 * см. [DetailsPickerSheet]. У манги меню нет: оно работает с каталогами аниме.
 *
 * Панель рисуется внутри экрана, а не в `DropdownMenu`/`Popup`: Popup — отдельное окно, и
 * `layerBackdrop` экрана оттуда не прочитать, меню получалось сплошным, а не стеклянным. Скрим и
 * панель — соседи кнопки, а не предки бэкдропа, поэтому запись слоя они не ломают.
 */
@Composable
internal fun BoxScope.DetailsOverflowMenu(
    backdrop: Backdrop,
    isDark: Boolean,
    ru: Boolean,
    onMarkWatched: () -> Unit,
    onChangeCover: () -> Unit,
    onWrongSource: () -> Unit,
    isSecret: Boolean,
    onToggleSecret: () -> Unit,
    /** false у манги: каталоги аниме ей не нужны, в меню остаётся только секретная папка. */
    showCatalogActions: Boolean = true,
) {
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (expanded) MotionTokens.menuPop() else MotionTokens.sheetDismissForced(),
        label = "detailsOverflow",
    )
    val modern = LocalModernUi.current
    val textColor = if (modern) MaterialTheme.colorScheme.onSurface else Color.White

    BackHandler(enabled = expanded) { expanded = false }

    // Невидимый скрим ловит тап вне меню; слой над контентом, под кнопкой и панелью.
    if (expanded) {
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(3f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = false },
        )
    }

    DetailsGlassBackButton(
        backdrop = backdrop,
        isDark = isDark,
        onClick = {
            performHaptic(view, Haptic.Light)
            expanded = !expanded
        },
        icon = Icons.Rounded.MoreHoriz,
        contentDescription = if (ru) "Ещё" else "More",
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 12.dp, end = 16.dp)
            .zIndex(4f),
    )

    if (progress > 0.001f) {
        val tint = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.18f)
        val border = Color.White.copy(alpha = if (isDark) 0.22f else 0.28f)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                // Под кнопкой: 12 отступ + 48 высота + 8 зазор.
                .padding(top = 12.dp + 48.dp + 8.dp, end = 16.dp)
                .zIndex(4f)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 0f)
                    val s = 0.82f + 0.18f * progress
                    scaleX = s
                    scaleY = s
                    alpha = progress.coerceIn(0f, 1f)
                }
                .widthIn(min = 232.dp)
                .clip(PanelShape)
                .glassControl(backdrop, PanelShape) {
                    drawBackdrop(
                        backdrop = backdrop,
                        shape = { PanelShape },
                        effects = {
                            vibrancy()
                            blur(24f.dp.toPx())
                            lens(8f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(tint) },
                    ).border(0.5.dp, border, PanelShape)
                }
                .padding(vertical = 6.dp),
        ) {
            if (showCatalogActions) MenuRow(
                icon = Icons.Rounded.CheckCircle,
                label = if (ru) "Просмотрено" else "Mark as watched",
                tint = textColor,
                onClick = {
                    expanded = false
                    performHaptic(view, Haptic.Success)
                    onMarkWatched()
                },
            )
            if (showCatalogActions) MenuRow(
                icon = Icons.Rounded.Image,
                label = if (ru) "Сменить обложку" else "Change cover",
                tint = textColor,
                onClick = {
                    expanded = false
                    onChangeCover()
                },
            )
            if (showCatalogActions) MenuRow(
                icon = Icons.Rounded.LinkOff,
                label = if (ru) "Источник неверен" else "Wrong source",
                tint = BrandOrangeBright,
                onClick = {
                    expanded = false
                    onWrongSource()
                },
            )
            MenuRow(
                icon = if (isSecret) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                label = when {
                    isSecret && ru -> "Вернуть в ленту"
                    isSecret -> "Move back to collection"
                    ru -> "В секретную папку"
                    else -> "Move to secret folder"
                },
                tint = textColor,
                onClick = {
                    expanded = false
                    performHaptic(view, Haptic.Light)
                    onToggleSecret()
                },
            )
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}
