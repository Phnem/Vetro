package com.example.myapplication.ui.shared.components

import com.example.myapplication.ui.shared.glassControl
import com.example.myapplication.ui.shared.rememberPinnableBackdrop
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Стандартная «плавающая» шапка меню (гайдбук §11): стеклянный пузырёк-назад + стеклянная
 * капсула с названием, оба скользят поверх прокручиваемого контента под ними. Точно тот же
 * материал, что у дока на главном и у шапок Details / AddEdit — вынесен сюда, чтобы все экраны
 * использовали одну реализацию.
 *
 * @param backdrop слой-задник со ВСЕМ контентом под шапкой (обычно тот же [Backdrop], что
 *   помечает прокручиваемый список через `Modifier.layerBackdrop(backdrop)`).
 * @param iconModifier модификатор для иконки «назад» — сюда прокидывается `sharedElement(...)`,
 *   если шапка участвует в shared-transition (например, из дока настроек).
 */
@Composable
fun GlassMenuHeader(
    backdrop: Backdrop,
    title: String,
    /** null — пузырька «назад» нет: экран показан страницей, уходить некуда. */
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isDark: Boolean = isAppInDarkTheme(),
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    iconModifier: Modifier = Modifier,
) {
    val view = LocalView.current
    // Шапка стоит внутри страниц рабочей области — во время свайпа её стекло не должно
    // пересчитываться на каждом кадре (см. PinnableBackdrop).
    val source = rememberPinnableBackdrop(backdrop)
    // Лёгкий фрост поверх линзы, чтобы капсула читалась и на плоском (не-hero) фоне списка.
    val surfaceTint = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.55f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .statusBarsPadding()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp),
    ) {
        // — Пузырёк «назад» —
        if (onBack != null) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .clip(CircleShape)
                .glassControl(source, CircleShape) {
                    drawBackdrop(
                        backdrop = source,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(12f.dp.toPx())
                            lens(8f.dp.toPx(), 40f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(surfaceTint) },
                    ).border(0.5.dp, borderColor, CircleShape)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { performHaptic(view, Haptic.Light); onBack() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = backIcon,
                contentDescription = "Back",
                tint = contentColor,
                modifier = iconModifier.size(24.dp),
            )
        }
        }

        // — Капсула с названием —
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 240.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(100.dp))
                .glassControl(source, RoundedCornerShape(100.dp)) {
                    drawBackdrop(
                        backdrop = source,
                        shape = { RoundedCornerShape(100.dp) },
                        effects = {
                            vibrancy()
                            blur(12f.dp.toPx())
                            lens(8f.dp.toPx(), 40f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(surfaceTint) },
                    ).border(0.5.dp, borderColor, RoundedCornerShape(100.dp))
                }
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
