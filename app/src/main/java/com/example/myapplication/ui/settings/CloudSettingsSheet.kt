package com.example.myapplication.ui.settings

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.AuthProviderIcon
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.utils.getStrings
import com.phnem.vetro.R
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * «Настройки аккаунта»: синхронизация с облаком, способы входа и пароль для входа по email —
 * карточками в языке панели «Дополнительные источники» (см. SettingsPanelParts).
 */
@Composable
fun CloudSettingsSheet(
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    sharedModifier: Modifier = Modifier,
) {
    val authRepository: com.example.myapplication.sync.supabase.AuthRepository = koinInject()
    val supabaseSyncCoordinator: com.example.myapplication.sync.supabase.SupabaseSyncCoordinator = koinInject()
    val context = LocalContext.current
    val settingsVm: SettingsViewModel = koinViewModel()
    val uiState by settingsVm.uiState.collectAsStateWithLifecycle()
    val strings = getStrings(uiState.language)
    val ru = uiState.language == AppLanguage.RU
    val scope = rememberCoroutineScope()
    val isDark = isAppInDarkTheme()

    val isUserSignedIn by authRepository.isUserSignedIn.collectAsStateWithLifecycle(initialValue = false)
    val linkedProviders by authRepository.linkedProvidersFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val isSyncing by supabaseSyncCoordinator.isSyncing.collectAsStateWithLifecycle(initialValue = false)
    val lastSyncMessage by supabaseSyncCoordinator.lastSyncMessage.collectAsStateWithLifecycle(initialValue = null)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    SettingsPanelColumn(
        modifier = sharedModifier
            .heightIn(max = maxSheetHeight),
    ) {
        SettingsPanelHeader(
            title = strings.cloudSettingsTitle,
            doneLabel = if (ru) "Готово" else "Done",
            onDone = onDismiss,
        )

        if (isUserSignedIn) {
            // 1. Синхронизация.
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelGlyphTile(Icons.Filled.Sync)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ru) "Синхронизация с облаком" else "Cloud sync",
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (ru) "Резервное копирование и синхронизация коллекции" else "Backup and sync your collection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SnProFamily,
                        )
                    }
                }
                lastSyncMessage?.let { message ->
                    val isError = message.startsWith("Upload:") || message.startsWith("Download:")
                    Text(
                        text = formatSyncMessageForDisplay(message),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SnProFamily,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PanelButton(
                        text = if (ru) "Выйти" else "Log out",
                        tone = PanelTone.Danger,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                authRepository.signOut()
                                onLogout()
                            }
                        },
                    )
                    PanelButton(
                        text = strings.syncLabel,
                        busy = isSyncing,
                        modifier = Modifier.weight(1f),
                        onClick = { supabaseSyncCoordinator.syncNow(includeCloudImageRestore = true) },
                    )
                }
            }

            // 2. Способы входа.
            val hasGoogle = "google" in linkedProviders
            val hasGithub = "github" in linkedProviders
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelGlyphTile(Icons.Filled.Person)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ru) "Способы входа" else "Sign-in methods",
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold,
                        )
                        authRepository.currentUserEmail?.let {
                            Text(
                                "Email: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = SnProFamily,
                            )
                        }
                        authRepository.currentUserId?.let {
                            Text(
                                if (ru) "ID аккаунта: $it" else "Account ID: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = SnProFamily,
                            )
                        }
                    }
                }
                Text(
                    text = linkHint(ru, hasGoogle, hasGithub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SnProFamily,
                )
            }
            ProviderCard(
                iconRes = R.drawable.ic_google,
                label = "Google",
                linked = hasGoogle,
                ru = ru,
                isDark = isDark,
                onLink = {
                    scope.launch {
                        val result = authRepository.linkGoogle()
                        if (result.isFailure) toast(result.exceptionOrNull()?.message ?: "Link failed")
                    }
                },
            )
            ProviderCard(
                iconRes = R.drawable.ic_github,
                label = "GitHub",
                linked = hasGithub,
                ru = ru,
                isDark = isDark,
                onLink = {
                    scope.launch {
                        val result = authRepository.linkGithub()
                        if (result.isFailure) toast(result.exceptionOrNull()?.message ?: "Link failed")
                    }
                },
            )

            // 3. Пароль для входа по email.
            PasswordCard(
                ru = ru,
                onSave = { password ->
                    scope.launch {
                        val result = authRepository.setPassword(password)
                        if (result.isSuccess) {
                            toast(
                                if (ru) "Пароль сохранён — теперь можно входить по email"
                                else "Password saved — you can now sign in with email",
                            )
                        } else {
                            toast(result.exceptionOrNull()?.message ?: "Unknown error")
                        }
                    }
                },
            )
        } else {
            PanelRowCard(
                title = if (ru) "Войти через Google" else "Sign in with Google",
                summary = if (ru) "Резервная копия и синхронизация между устройствами" else "Backup and sync across devices",
                onClick = {
                    scope.launch {
                        val result = authRepository.signInWithGoogle(context)
                        toast(if (result.isSuccess) "Logged in!" else "Login failed")
                    }
                },
                leading = {
                    PanelIconTile {
                        AuthProviderIcon(
                            iconRes = R.drawable.ic_google,
                            modifier = Modifier.size(24.dp),
                            isDarkTheme = isDark,
                        )
                    }
                },
            )
        }
    }
}

/** Способ входа: привязан — статус акцентом, иначе вся карточка нажимается и привязывает. */
@Composable
private fun ProviderCard(
    @DrawableRes iconRes: Int,
    label: String,
    linked: Boolean,
    ru: Boolean,
    isDark: Boolean,
    onLink: () -> Unit,
) {
    PanelRowCard(
        title = label,
        status = if (linked) {
            if (ru) "Привязан" else "Linked"
        } else {
            if (ru) "Привязать" else "Link"
        },
        onClick = if (linked) null else onLink,
        leading = {
            PanelIconTile {
                AuthProviderIcon(
                    iconRes = iconRes,
                    modifier = Modifier.size(24.dp),
                    isDarkTheme = isDark,
                )
            }
        },
    )
}

@Composable
private fun PasswordCard(ru: Boolean, onSave: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var fieldsVisible by remember { mutableStateOf(true) }
    var revealed by remember { mutableStateOf(false) }
    val canSave = password.length >= 6 && password == confirmPassword

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelGlyphTile(Icons.Filled.Lock)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (ru) "Пароль для входа по email" else "Email sign-in password",
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (ru) "Если вы входили только через Google или GitHub, задайте пароль здесь."
                    else "If you signed in with Google or GitHub only, set a password here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SnProFamily,
                )
            }
        }
        PanelPillButton(
            text = when {
                fieldsVisible && ru -> "У меня уже есть пароль"
                fieldsVisible -> "I already have a password"
                ru -> "Задать или сменить пароль"
                else -> "Set or change password"
            },
            icon = if (fieldsVisible) Icons.Filled.Check else Icons.Filled.Edit,
            onClick = { fieldsVisible = !fieldsVisible },
        )
        AnimatedVisibility(visible = fieldsVisible, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PanelPillField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = if (ru) "Новый пароль" else "New password",
                    icon = Icons.Filled.Lock,
                    masked = !revealed,
                    revealIcon = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    onToggleReveal = { revealed = !revealed },
                )
                PanelPillField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    placeholder = if (ru) "Повтор пароля" else "Confirm password",
                    icon = Icons.Filled.Lock,
                    masked = !revealed,
                )
                PanelButton(
                    text = if (ru) "Сохранить пароль" else "Save password",
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onSave(password)
                        password = ""
                        confirmPassword = ""
                    },
                )
            }
        }
    }
}

private fun linkHint(ru: Boolean, hasGoogle: Boolean, hasGithub: Boolean): String = when {
    hasGoogle && hasGithub && ru ->
        "Google и GitHub привязаны к одному аккаунту — можно входить любым способом. ID на обоих устройствах должен совпадать."
    hasGoogle && hasGithub ->
        "Google and GitHub are linked — sign in with either. Account ID must match on all devices."
    hasGoogle && ru ->
        "Привязан только Google. На основном устройстве нажми «Привязать GitHub», чтобы объединить аккаунты."
    hasGoogle ->
        "Only Google is linked. On your main device, tap Link GitHub to merge accounts."
    hasGithub && ru ->
        "Привязан только GitHub. На основном устройстве нажми «Привязать Google», чтобы объединить аккаунты."
    hasGithub ->
        "Only GitHub is linked. On your main device, tap Link Google to merge accounts."
    ru -> "Привяжи второй способ входа на том устройстве, где уже есть коллекция."
    else -> "Link a second sign-in method on the device that already has your collection."
}

private fun formatSyncMessageForDisplay(message: String): String {
    val prefix = when {
        message.startsWith("Upload:") -> "Upload:"
        message.startsWith("Download:") -> "Download:"
        else -> return message
    }
    val body = message.removePrefix(prefix).trim()
    val firstLine = body.lineSequence().firstOrNull()?.trim().orEmpty()
    val withoutHeaders = firstLine.substringBefore("Headers:").trim()
    val withoutUrl = withoutHeaders.substringBefore("URL:").trim()
    return if (withoutUrl.isBlank()) message else "$prefix $withoutUrl"
}
