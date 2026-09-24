package com.example.myapplication.ui.settings

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.ai.AiProvider
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.getAiConnectStrings
import com.example.myapplication.utils.getStrings
import org.koin.androidx.compose.koinViewModel

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

    val isDark = isAppInDarkTheme()
    val uriHandler = LocalUriHandler.current

    val sheetSurface = if (isDark) {
        Color(0xFF000000).copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    }
    val onSurface = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = OverlayThemeTokens.AccentNeonGreen

    Column(
        // Панель рисует IosSheetScaffold (общий iosSheetContainer) — здесь только контент.
        modifier = sharedModifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
            .padding(horizontal = 20.dp)
            .padding(top = IosDesign.SheetContentTop, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.sheetTitle,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                    fontFamily = SnProFamily,
                    color = onSurface,
                )
                Text(
                    text = strings.sheetSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = SnProFamily,
                    color = muted,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = strings.doneButton, tint = muted)
            }
        }

        OutlinedTextField(
            value = uiState.input,
            onValueChange = viewModel::onInputChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !uiState.isBusy,
            placeholder = { Text(strings.inputPlaceholder, fontFamily = SnProFamily) },
            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, tint = muted) },
            trailingIcon = {
                IconButton(onClick = viewModel::toggleShowKey) {
                    Icon(
                        imageVector = if (uiState.showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (uiState.showKey) strings.hideKeyCd else strings.showKeyCd,
                        tint = muted,
                    )
                }
            },
            visualTransformation = if (uiState.showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                focusedContainerColor = if (isDark) Color(0xFF1C1C1C) else MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = if (isDark) Color(0xFF1C1C1C) else MaterialTheme.colorScheme.surface,
                focusedTextColor = onSurface,
                unfocusedTextColor = onSurface,
                cursorColor = accent,
            ),
        )

        StatusLine(uiState = uiState, strings = accentStatus(strings), muted = muted)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { uriHandler.openUri(viewModel.apiKeyUrlForCurrentInput()) }) {
                Text(strings.getApiKey, fontFamily = SnProFamily, color = accent)
            }
            Button(
                onClick = viewModel::connect,
                enabled = uiState.input.isNotBlank() && !uiState.isBusy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            ) {
                if (uiState.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(strings.connectButton, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Text(
            text = strings.storageHint,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = SnProFamily,
            color = muted,
        )

        if (uiState.connected.isEmpty()) {
            Text(
                text = strings.emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = SnProFamily,
                color = muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                uiState.connected.forEach { provider ->
                    ConnectedProviderPill(
                        provider = provider,
                        connectedLabel = strings.connectedLabel,
                        removeCd = strings.removeCd,
                        isDark = isDark,
                        onDelete = { viewModel.delete(provider) },
                    )
                }
            }
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
        ) {
            Text(strings.doneButton, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Текст статуса под полем: цвет ошибки красный, иначе — нейтральный. */
@Composable
private fun StatusLine(
    uiState: AiConnectUiState,
    strings: AiConnectStatusStrings,
    muted: Color,
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
        color = if (isError) MaterialTheme.colorScheme.error else muted,
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

@Composable
private fun ConnectedProviderPill(
    provider: AiProvider,
    connectedLabel: String,
    removeCd: String,
    isDark: Boolean,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val outline = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val onSurface = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, outline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(provider.iconRes),
            contentDescription = null,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = provider.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            color = onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .background(OverlayThemeTokens.IconSignalGreen.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = connectedLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                color = OverlayThemeTokens.IconSignalGreen,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = removeCd,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
