package com.example.myapplication.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape

/*
 * Общий язык панелей настроек: тот же, что у «Дополнительных источников» — заголовок с «Готово»,
 * плотные скруглённые карточки с плиткой-значком, названием, пояснением и статусом акцентом.
 * Панели «Настройки аккаунта», AI Connect и «Обогащение коллекции» собираются только из этого.
 */

/** Колонка панели: цвет содержимого задан явно (хост листа его не объявляет), отступы как у эталона. */
@Composable
internal fun SettingsPanelColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
internal fun SettingsPanelHeader(title: String, doneLabel: String, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDone) {
            Text(doneLabel, fontFamily = SnProFamily)
        }
    }
}

@Composable
internal fun PanelHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = SnProFamily,
        modifier = modifier,
    )
}

/** Карточка-контейнер для блоков с несколькими элементами (поля, кнопки). */
@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SquircleShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Плитка под значок: акцентная заливка 14% и скруглённый квадрат, как у карточек способов подключения. */
@Composable
internal fun PanelIconTile(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(SquircleShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun PanelGlyphTile(icon: ImageVector) {
    PanelIconTile {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

/** Строка-карточка: плитка, название, пояснение, статус акцентом и произвольный хвост (переключатель, кнопка). */
@Composable
internal fun PanelRowCard(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    status: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = modifier.fillMaxWidth()
    Surface(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        shape = SquircleShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SnProFamily,
                    )
                }
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

internal enum class PanelTone { Primary, Quiet, Danger }

/** Кнопка-пилюля панели: акцентная, тихая или опасная; при [busy] вместо подписи — индикатор. */
@Composable
internal fun PanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PanelTone = PanelTone.Primary,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val background: Color
    val foreground: Color
    when (tone) {
        PanelTone.Primary -> if (enabled) {
            background = scheme.primary
            foreground = Color.White
        } else {
            background = scheme.onSurface.copy(alpha = 0.08f)
            foreground = scheme.onSurfaceVariant
        }
        PanelTone.Quiet -> {
            background = scheme.onSurface.copy(alpha = 0.08f)
            foreground = scheme.onSurface
        }
        PanelTone.Danger -> {
            background = scheme.error.copy(alpha = 0.14f)
            foreground = scheme.error
        }
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = foreground, strokeWidth = 2.dp)
        } else {
            Text(text, color = foreground, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Цвета текстовых полей внутри карточек: оранжевый акцент вместо неонового, фон — как у карточки. */
@Composable
internal fun panelFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/** Приподнятый «пузырь» полей и кнопок: мягкий градиент, тонкий блик по контуру и лёгкая тень. */
@Composable
private fun Modifier.raisedBubble(shape: Shape): Modifier {
    val dark = isAppInDarkTheme()
    val top = if (dark) Color.White.copy(alpha = 0.20f) else Color.White
    val bottom = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.82f)
    val edge = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.05f)
    return this
        .shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.30f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(0.8.dp, edge, shape)
}

/** Вдавленная капсула под поле ввода: чуть темнее карточки, тонкая окантовка, в фокусе — акцентная. */
@Composable
private fun Modifier.recessedPill(focused: Boolean): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = isAppInDarkTheme()
    val rim = when {
        focused -> scheme.primary.copy(alpha = 0.85f)
        dark -> Color.White.copy(alpha = 0.10f)
        else -> Color.Black.copy(alpha = 0.06f)
    }
    return this
        .clip(CircleShape)
        .background(
            Brush.verticalGradient(
                listOf(
                    scheme.onSurface.copy(alpha = if (dark) 0.04f else 0.03f),
                    scheme.onSurface.copy(alpha = if (dark) 0.10f else 0.07f),
                ),
            ),
        )
        .border(if (focused) 1.5.dp else 1.dp, rim, CircleShape)
}

/**
 * Поле ввода-капсула: слева круглая плитка со значком, посередине текст, справа (если нужно) —
 * капсула-переключатель, например «показать ключ». Вместо прямоугольного OutlinedTextField.
 */
@Composable
internal fun PanelPillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    masked: Boolean = false,
    revealIcon: ImageVector? = null,
    onToggleReveal: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .recessedPill(focused)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).raisedBubble(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = scheme.onSurfaceVariant,
                    fontFamily = SnProFamily,
                    fontSize = 17.sp,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = scheme.onSurface, fontFamily = SnProFamily, fontSize = 17.sp),
                cursorBrush = SolidColor(scheme.primary),
                visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (onToggleReveal != null && revealIcon != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(48.dp)
                    .width(68.dp)
                    .raisedBubble(CircleShape)
                    .clickable(onClick = onToggleReveal),
                contentAlignment = Alignment.Center,
            ) {
                Icon(revealIcon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Второстепенная кнопка-капсула: тёмная круглая плитка со значком и подпись, приподнятая над
 * карточкой. Заменяет голые текстовые кнопки.
 */
@Composable
internal fun PanelPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** Вдвое меньше обычной: для тесных рядов, где рядом стоит основная кнопка. */
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val height = if (compact) 26.dp else 52.dp
    val tile = if (compact) 18.dp else 36.dp
    val glyph = if (compact) 11.dp else 20.dp
    Row(
        modifier = modifier
            .height(height)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .raisedBubble(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                start = if (icon != null) (if (compact) 4.dp else 8.dp) else (if (compact) 11.dp else 22.dp),
                end = if (compact) 11.dp else 22.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(tile).clip(CircleShape).background(scheme.onSurface), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = scheme.background, modifier = Modifier.size(glyph))
            }
            Spacer(Modifier.width(if (compact) 6.dp else 12.dp))
        }
        Text(
            text,
            color = scheme.onSurface,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 12.sp else 16.sp,
            maxLines = 1,
        )
    }
}
