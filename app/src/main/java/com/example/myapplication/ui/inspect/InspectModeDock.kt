package com.example.myapplication.ui.inspect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
import com.phnem.vetro.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily

/**
 * Нижний док выбора режима поиска по кадру. Один в один мини-док из Details (`DetailsMiniDock`):
 * тёмная капсула, активная вкладка — светлая полупрозрачная пилюля, подпись у которой ВЫЕЗЖАЕТ по
 * ширине (expandHorizontally), неактивная — только приглушённая иконка без текста. Без акцентной
 * заливки: акцент в доке спорит с оранжевой кнопкой действия на карточке результата.
 *
 * Заливка статичная, без сэмплирования бэкдропа: экран открывается через shared-element переход,
 * и записанный GraphicsLayer бэкдропа внутри него роняет RenderThread (см. [InspectScreen]).
 */
@Composable
fun InspectModeDock(
    animeLabel: String,
    moviesLabel: String,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dockShape = CircleShape
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(dockShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), dockShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InspectModeItem(
            icon = R.drawable.ph_television_simple,
            label = animeLabel,
            selected = selectedIndex == 0,
            onClick = { onSelect(0) },
        )
        InspectModeItem(
            icon = R.drawable.ph_film_slate_fill,
            label = moviesLabel,
            selected = selectedIndex == 1,
            onClick = { onSelect(1) },
        )
    }
}

@Composable
private fun InspectModeItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = Color.White
    val pillBg by animateColorAsState(
        targetValue = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = MotionTokens.standard(),
        label = "inspectDockPillBg",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) contentColor else contentColor.copy(alpha = 0.55f),
        animationSpec = MotionTokens.standard(),
        label = "inspectDockTint",
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(pillBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(animationSpec = MotionTokens.standard()) + fadeIn(tween(180)),
            exit = shrinkHorizontally(animationSpec = MotionTokens.sheetDismissForced()) + fadeOut(tween(120)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(7.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}
