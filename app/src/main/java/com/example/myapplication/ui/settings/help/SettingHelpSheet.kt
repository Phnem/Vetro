package com.example.myapplication.ui.settings.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.components.SettingHelpRequest
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic

/**
 * Содержимое шторки с пояснением к пункту настроек (контейнер и «ручку» даёт экран).
 *
 * Внизу две кнопки, которые заменяют сам пункт: серая «Назад» и оранжевая. У переключателя оранжевая
 * включает или выключает настройку («Включить» / «Выключить»), у остальных пунктов — «Перейти»:
 * закрывает описание и открывает сам пункт.
 */
@Composable
fun SettingHelpSheet(
    request: SettingHelpRequest,
    text: SettingHelpText,
    ru: Boolean,
    isDark: Boolean,
    onBack: () -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
    /** Дополнительный блок под пунктами (например, «Что будет отправлено» у сбора данных). */
    extra: (@Composable () -> Unit)? = null,
) {
    val view = LocalView.current
    val binding = request.binding
    val isToggle = binding.checked != null && binding.onToggle != null
    // Шторка остаётся открытой после переключения — значение держим локально, а не ждём перерисовки.
    var checked by remember(request) { mutableStateOf(binding.checked ?: false) }
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        Text(
            text = text.title,
            color = onSurface,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 31.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = text.lead,
            color = onSurface.copy(alpha = 0.82f),
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))

        text.points.forEachIndexed { index, point ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(BrandOrange.copy(alpha = if (isDark) 0.22f else 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = BrandOrange,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = point.heading,
                        color = onSurface,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = point.body,
                        color = onSurface.copy(alpha = 0.62f),
                        fontFamily = SnProFamily,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (extra != null) {
            extra()
            Spacer(Modifier.height(16.dp))
        }

        text.footnote?.let { note ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(onSurface.copy(alpha = if (isDark) 0.08f else 0.05f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = note,
                    color = onSurface.copy(alpha = 0.66f),
                    fontFamily = SnProFamily,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetCapsule(
                label = if (ru) "Назад" else "Back",
                container = if (isDark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA),
                content = onSurface,
                modifier = Modifier.weight(1f),
                onClick = {
                    performHaptic(view, Haptic.Light)
                    onBack()
                },
            )
            val primaryLabel = when {
                isToggle && !checked -> if (ru) "Включить" else "Turn on"
                isToggle -> if (ru) "Выключить" else "Turn off"
                else -> if (ru) "Перейти" else "Open"
            }
            if (isToggle || request.onGo != null) {
                SheetCapsule(
                    label = primaryLabel,
                    container = BrandOrange,
                    content = Color.White,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        performHaptic(view, Haptic.Light)
                        if (isToggle) {
                            val next = !checked
                            checked = next
                            binding.onToggle?.invoke(next)
                        } else {
                            onGo()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetCapsule(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(container)
            .fluidClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
