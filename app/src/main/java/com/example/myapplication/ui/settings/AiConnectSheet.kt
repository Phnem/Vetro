package com.example.myapplication.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalUriHandler
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.getAiConnectStrings
import org.koin.androidx.compose.koinViewModel

/**
 * AI Connect: ключ провайдера, статус проверки и список подключённых провайдеров — карточками
 * в языке панели «Дополнительные источники» (см. SettingsPanelParts).
 */
@Composable
fun AiConnectSheet(
    onDismiss: () -> Unit,
    sharedModifier: Modifier = Modifier,
) {
    val settingsVm: SettingsViewModel = koinViewModel()
    val settingsState by settingsVm.uiState.collectAsStateWithLifecycle()
    val strings = getAiConnectStrings(settingsState.language)

    val viewModel: AiConnectViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SettingsPanelColumn(modifier = sharedModifier.heightIn(max = 640.dp)) {
        SettingsPanelHeader(title = strings.sheetTitle, doneLabel = strings.doneButton, onDone = onDismiss)
        PanelHint(strings.sheetSubtitle)

        PanelCard {
            PanelPillField(
                value = uiState.input,
                onValueChange = viewModel::onInputChange,
                placeholder = strings.inputPlaceholder,
                icon = Icons.Outlined.Key,
                enabled = !uiState.isBusy,
                masked = !uiState.showKey,
                revealIcon = if (uiState.showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                onToggleReveal = viewModel::toggleShowKey,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
            )
            StatusLine(uiState = uiState, strings = accentStatus(strings))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelPillButton(
                    // Коротко: полная подпись «Получить ключ» вытесняла «Подключить» и переносила её на две строки.
                    text = if (settingsState.language == AppLanguage.RU) "Получить" else "Get",
                    icon = Icons.Filled.QuestionMark,
                    compact = true,
                    onClick = { uriHandler.openUri(viewModel.apiKeyUrlForCurrentInput()) },
                )
                PanelButton(
                    text = strings.connectButton,
                    onClick = viewModel::connect,
                    enabled = uiState.input.isNotBlank() && !uiState.isBusy,
                    busy = uiState.isBusy,
                )
            }
            Text(
                text = strings.storageHint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = SnProFamily,
                color = muted,
            )
        }

        if (uiState.connected.isEmpty()) {
            PanelHint(strings.emptyHint)
        } else {
            uiState.connected.forEach { provider ->
                PanelRowCard(
                    title = provider.displayName,
                    status = strings.connectedLabel,
                    leading = {
                        PanelIconTile {
                            Image(
                                painter = painterResource(provider.iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    },
                    trailing = {
                        IconButton(onClick = { viewModel.delete(provider) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = strings.removeCd,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** Текст статуса под полем: цвет ошибки красный, иначе — нейтральный. */
@Composable
private fun StatusLine(
    uiState: AiConnectUiState,
    strings: AiConnectStatusStrings,
) {
    val (text, isError) = when (uiState.status) {
        AiConnectStatus.Detecting -> strings.detecting to false
        AiConnectStatus.Validating -> strings.validating to false
        AiConnectStatus.Error -> strings.error to true
        AiConnectStatus.Idle -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = SnProFamily,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class AiConnectStatusStrings(
    val detecting: String,
    val validating: String,
    val error: String,
)

private fun accentStatus(s: com.example.myapplication.utils.AiConnectStrings) = AiConnectStatusStrings(
    detecting = s.statusDetecting,
    validating = s.statusValidating,
    error = s.statusError,
)
