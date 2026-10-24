package com.example.myapplication.ui.settings

import com.example.myapplication.ui.shared.theme.StatusSuccess
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.BrandOrange
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.components.IosIconWell
import com.example.myapplication.ui.shared.components.IosSwitch
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.getCollectionEnrichmentStrings
import kotlinx.coroutines.delay


/**
 * «Обогащение коллекции» — модуль 1 (кнопка «Полное обогащение») и модуль 2 (тумблер
 * «Фоновое обновление»). Прогресс полного прогона отражают isRepairingDb (фаза полей) и
 * isTitleDubbing (фаза названий) из SettingsViewModel. Вёрстка — карточки панели
 * «Дополнительные источники» (см. SettingsPanelParts).
 */
@Composable
fun CollectionEnrichmentSheet(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    sharedModifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = getCollectionEnrichmentStrings(uiState.language)
    val ru = uiState.language == AppLanguage.RU

    val running = uiState.isRepairingDb || uiState.isTitleDubbing
    val fullSubtitle = when {
        uiState.isRepairingDb -> strings.fullRunningFields
        uiState.isTitleDubbing && uiState.titleDubbingTotal > 0 ->
            strings.fullRunningTitlesTemplate.format(uiState.titleDubbingProcessed, uiState.titleDubbingTotal)
        uiState.isTitleDubbing -> strings.fullRunningFields
        else -> strings.fullSubtitle
    }

    SettingsPanelColumn(modifier = sharedModifier) {
        SettingsPanelHeader(
            title = strings.sheetTitle,
            doneLabel = if (ru) "Готово" else "Done",
            onDone = onDismiss,
        )

        // Модуль 1: полное обогащение (нажатие на карточку).
        PanelRowCard(
            title = strings.fullTitle,
            summary = fullSubtitle,
            onClick = if (running) null else { { viewModel.runFullEnrichment() } },
            leading = { PanelGlyphTile(Icons.Filled.AutoFixHigh) },
            trailing = if (running) {
                {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                }
            } else null,
        )

        // Модуль 2: фоновое обновление (тумблер).
        PanelRowCard(
            title = strings.liveTitle,
            summary = strings.liveSubtitle,
            leading = { PanelGlyphTile(Icons.Filled.Autorenew) },
            trailing = {
                IosSwitch(
                    checked = uiState.liveMaintenanceEnabled,
                    onCheckedChange = { viewModel.setLiveMaintenance(it) },
                )
            },
        )
    }
}

/**
 * Незакрываемый диалог порога перегрузки: «Отмена» гасит фон, «Начать» превращается в зелёную
 * пилюлю с галочкой и плавно запускает полное обогащение.
 */
@Composable
fun FullEnrichmentPromptDialog(
    gapCount: Int,
    viewModel: SettingsViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = getCollectionEnrichmentStrings(uiState.language)
    val sheetInDark = isAppInDarkTheme()
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(confirming) {
        if (confirming) {
            delay(650)
            viewModel.confirmFullEnrichmentPrompt()
        }
    }

    Dialog(
        onDismissRequest = { /* незакрываемый */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (sheetInDark) OverlayThemeTokens.TileBackgroundDark else Color.White)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings.overflowTitle,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = strings.overflowMessageTemplate.format(gapCount),
                fontFamily = SnProFamily,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedContent(
                targetState = confirming,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                label = "promptConfirm",
            ) { isConfirming ->
                if (isConfirming) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(StatusSuccess)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PromptButton(
                            text = strings.overflowCancel,
                            fill = false,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.cancelFullEnrichmentPrompt() },
                        )
                        PromptButton(
                            text = strings.overflowStart,
                            fill = true,
                            modifier = Modifier.weight(1f),
                            onClick = { confirming = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptButton(
    text: String,
    fill: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val sheetInDark = isAppInDarkTheme()
    val bg = if (fill) BrandOrange else {
        if (sheetInDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    }
    val fg = if (fill) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = SnProFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            textAlign = TextAlign.Center,
        )
    }
}
