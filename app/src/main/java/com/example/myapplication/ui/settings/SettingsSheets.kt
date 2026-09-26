package com.example.myapplication.ui.settings

import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.IosScroll
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.example.myapplication.data.models.AppUpdateStatus
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.phnem.vetro.R
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.utils.formatApkSizeLabel
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.example.myapplication.ui.shared.AuthProviderIcon
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.softPlateShadowForLightSheet
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.compose.ui.text.style.TextAlign
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun CloudSettingsSheet(
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    sharedModifier: Modifier = Modifier
) {
    val authRepository: com.example.myapplication.sync.supabase.AuthRepository = koinInject()
    val supabaseSyncCoordinator: com.example.myapplication.sync.supabase.SupabaseSyncCoordinator = koinInject()
    val context = LocalContext.current
    val settingsVm: SettingsViewModel = koinViewModel()
    val uiState by settingsVm.uiState.collectAsStateWithLifecycle()
    val strings = getStrings(uiState.language)
    val scope = rememberCoroutineScope()
    
    val isUserSignedIn by authRepository.isUserSignedIn.collectAsStateWithLifecycle(initialValue = false)
    val linkedProviders by authRepository.linkedProvidersFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val isSyncing by supabaseSyncCoordinator.isSyncing.collectAsStateWithLifecycle(initialValue = false)
    val lastSyncMessage by supabaseSyncCoordinator.lastSyncMessage.collectAsStateWithLifecycle(initialValue = null)

    val sheetInDarkTheme = isAppInDarkTheme()
    val sheetSurface = if (sheetInDarkTheme) {
        Color.Black.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    }
    val cloudPanelShape = RoundedCornerShape(24.dp)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp

    Box(
        modifier = sharedModifier
            .fillMaxWidth()
            .padding(top = IosDesign.SheetContentTop)
            .heightIn(max = maxSheetHeight),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = strings.cloudSettingsTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = SnProFamily,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                if (isUserSignedIn) {
                    CloudSettingsSection(
                        strings = CloudStrings(
                            title = if (uiState.language == AppLanguage.RU) "Синхронизация с облаком" else "Cloud Sync",
                            subtitle = if (uiState.language == AppLanguage.RU) {
                                "Резервное копирование и синхронизация коллекции"
                            } else {
                                "Backup and sync your collection"
                            },
                            syncNow = strings.syncLabel,
                            lastSync = strings.lastSync,
                            neverSynced = strings.never,
                            logout = if (uiState.language == AppLanguage.RU) "Выйти" else "Logout",
                        ),
                        lastSyncTime = 0L,
                        isSyncing = isSyncing,
                        onSyncClick = {
                            supabaseSyncCoordinator.syncNow(includeCloudImageRestore = true)
                        },
                        onLogout = {
                            scope.launch {
                                authRepository.signOut()
                                onLogout()
                            }
                        },
                    )

                    lastSyncMessage?.let { message ->
                        val isError = message.startsWith("Upload:") || message.startsWith("Download:")
                        Text(
                            text = formatSyncMessageForDisplay(message),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    LinkedAccountsSection(
                        isRussian = uiState.language == AppLanguage.RU,
                        email = authRepository.currentUserEmail,
                        userId = authRepository.currentUserId,
                        linkedProviders = linkedProviders,
                        onLinkGoogle = {
                            scope.launch {
                                val result = authRepository.linkGoogle()
                                if (result.isFailure) {
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: "Link failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onLinkGithub = {
                            scope.launch {
                                val result = authRepository.linkGithub()
                                if (result.isFailure) {
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: "Link failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    SetEmailPasswordSection(
                        isRussian = uiState.language == AppLanguage.RU,
                        onSave = { password ->
                            scope.launch {
                                val result = authRepository.setPassword(password)
                                if (result.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        if (uiState.language == AppLanguage.RU) {
                                            "Пароль сохранён — теперь можно входить по email"
                                        } else {
                                            "Password saved — you can now sign in with email"
                                        },
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                val result = authRepository.signInWithGoogle(context)
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Logged in!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Login failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AuthProviderIcon(
                                iconRes = R.drawable.ic_google,
                                modifier = Modifier.size(20.dp),
                                isDarkTheme = sheetInDarkTheme,
                            )
                            Text("Sign In with Google")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedAccountsSection(
    isRussian: Boolean,
    email: String?,
    userId: String?,
    linkedProviders: Set<String>,
    onLinkGoogle: () -> Unit,
    onLinkGithub: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    val titleColor = if (isDark) OverlayThemeTokens.AccentSyncBlue else MaterialTheme.colorScheme.primary
    val bodyColor = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val tileShape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius)
    val tileOutline = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
    val hasGoogle = "google" in linkedProviders
    val hasGithub = "github" in linkedProviders
    val bothLinked = hasGoogle && hasGithub

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (isRussian) "Способы входа" else "Sign-in methods",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            fontFamily = SnProFamily,
            color = titleColor,
        )
        email?.let {
            Text(
                text = if (isRussian) "Email: $it" else "Email: $it",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = SnProFamily,
                color = onSurface,
            )
        }
        userId?.let {
            Text(
                text = if (isRussian) "ID аккаунта: $it" else "Account ID: $it",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = SnProFamily,
                color = bodyColor,
            )
        }
        Text(
            text = when {
                bothLinked && isRussian ->
                    "Google и GitHub привязаны к одному аккаунту — можно входить любым способом. ID на обоих устройствах должен совпадать."
                bothLinked ->
                    "Google and GitHub are linked — sign in with either. Account ID must match on all devices."
                hasGoogle && isRussian ->
                    "Привязан только Google. На основном устройстве нажми «Привязать GitHub», чтобы объединить аккаунты."
                hasGoogle ->
                    "Only Google is linked. On your main device, tap Link GitHub to merge accounts."
                hasGithub && isRussian ->
                    "Привязан только GitHub. На основном устройстве нажми «Привязать Google», чтобы объединить аккаунты."
                hasGithub ->
                    "Only GitHub is linked. On your main device, tap Link Google to merge accounts."
                isRussian ->
                    "Привяжи второй способ входа на том устройстве, где уже есть коллекция."
                else ->
                    "Link a second sign-in method on the device that already has your collection."
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = SnProFamily,
            color = bodyColor,
        )
        if (hasGoogle) {
            LinkedProviderChip(
                iconRes = R.drawable.ic_google,
                label = "Google",
                isDark = isDark,
                tileShape = tileShape,
                tileOutline = tileOutline,
            )
        } else {
            CloudOutlinedActionButton(
                iconRes = R.drawable.ic_google,
                text = if (isRussian) "Привязать Google" else "Link Google",
                isDark = isDark,
                onClick = onLinkGoogle,
            )
        }
        if (hasGithub) {
            LinkedProviderChip(
                iconRes = R.drawable.ic_github,
                label = "GitHub",
                isDark = isDark,
                tileShape = tileShape,
                tileOutline = tileOutline,
            )
        } else {
            CloudOutlinedActionButton(
                iconRes = R.drawable.ic_github,
                text = if (isRussian) "Привязать GitHub" else "Link GitHub",
                isDark = isDark,
                onClick = onLinkGithub,
            )
        }
    }
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

@Composable
private fun LinkedProviderChip(
    @DrawableRes iconRes: Int,
    label: String,
    isDark: Boolean,
    tileShape: RoundedCornerShape,
    tileOutline: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tileShape)
            .border(1.dp, tileOutline, tileShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AuthProviderIcon(
                iconRes = iconRes,
                modifier = Modifier.size(20.dp),
                isDarkTheme = isDark,
            )
            Text(
                text = label,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "✓",
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            color = OverlayThemeTokens.IconSignalGreen,
        )
    }
}

@Composable
private fun CloudOutlinedActionButton(
    @DrawableRes iconRes: Int,
    text: String,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius),
        border = BorderStroke(
            1.dp,
            if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isDark) Color(0xFF1C1C1C) else Color.Transparent,
            contentColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AuthProviderIcon(
                iconRes = iconRes,
                modifier = Modifier.size(20.dp),
                isDarkTheme = isDark,
            )
            Text(text, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SetEmailPasswordSection(
    isRussian: Boolean,
    onSave: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordFieldsVisible by remember { mutableStateOf(true) }
    val canSave = password.length >= 6 && password == confirmPassword
    val isDark = isAppInDarkTheme()
    val titleColor = if (isDark) OverlayThemeTokens.AccentSyncBlue else MaterialTheme.colorScheme.primary
    val bodyColor = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val fieldShape = RoundedCornerShape(12.dp)
    val syncBrush = Brush.horizontalGradient(
        colors = listOf(OverlayThemeTokens.AccentSyncBlue, BrandDeepRed),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (isRussian) "Пароль для входа по email" else "Email sign-in password",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            fontFamily = SnProFamily,
            color = titleColor,
        )
        Text(
            text = if (isRussian) {
                "Если вы входили только через Google или GitHub, задайте пароль здесь."
            } else {
                "If you signed in with Google or GitHub only, set a password here."
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = SnProFamily,
            color = bodyColor,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (passwordFieldsVisible) {
                TextButton(onClick = { passwordFieldsVisible = false }) {
                    Text(
                        text = if (isRussian) "У меня уже есть пароль" else "I already have a password",
                        fontFamily = SnProFamily,
                        color = titleColor,
                    )
                }
            } else {
                TextButton(onClick = { passwordFieldsVisible = true }) {
                    Text(
                        text = if (isRussian) "Задать или сменить пароль" else "Set or change password",
                        fontFamily = SnProFamily,
                        color = titleColor,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = passwordFieldsVisible,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(
                            if (isRussian) "Новый пароль" else "New password",
                            fontFamily = SnProFamily,
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = fieldShape,
                    colors = cloudSheetFieldColors(isDark),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = {
                        Text(
                            if (isRussian) "Повтор пароля" else "Confirm password",
                            fontFamily = SnProFamily,
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = fieldShape,
                    colors = cloudSheetFieldColors(isDark),
                )
                Button(
                    onClick = {
                        onSave(password)
                        password = ""
                        confirmPassword = ""
                    },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .then(if (canSave) Modifier.background(syncBrush, CircleShape) else Modifier),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canSave) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        contentColor = if (canSave) OverlayThemeTokens.OnSyncBlueButton else bodyColor,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        disabledContentColor = bodyColor,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp),
                ) {
                    Text(
                        if (isRussian) "Сохранить пароль" else "Save password",
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun cloudSheetFieldColors(isDark: Boolean) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = OverlayThemeTokens.AccentSyncBlue,
    unfocusedBorderColor = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    focusedContainerColor = if (isDark) Color(0xFF1C1C1C) else MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = if (isDark) Color(0xFF1C1C1C) else MaterialTheme.colorScheme.surface,
    focusedTextColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = OverlayThemeTokens.LabelMutedDark,
    unfocusedLabelColor = OverlayThemeTokens.LabelMutedDark,
    cursorColor = OverlayThemeTokens.AccentSyncBlue,
)

@Composable
fun ContactSheet(
    onDismiss: () -> Unit,
    sharedModifier: Modifier = Modifier
) {
    val settingsVm: SettingsViewModel = koinViewModel()
    val uiState by settingsVm.uiState.collectAsStateWithLifecycle()
    val strings = getStrings(uiState.language)
    val context = LocalContext.current
    val view = LocalView.current
    val sheetInDark = isAppInDarkTheme()

    Column(
        modifier = sharedModifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = IosDesign.SheetContentTop, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = strings.contactTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                ),
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = strings.contactSupportSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ContactActionCard(
                iconId = R.drawable.ic_github,
                title = "GitHub",
                onClick = {
                    performHaptic(view, Haptic.Light)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Phnem/Vetro-Collection")))
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            )
            ContactActionCard(
                iconId = R.drawable.tg,
                title = "Telegram",
                onClick = {
                    performHaptic(view, Haptic.Light)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Vetro_chat")))
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun UpdateChangelogSheet(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    installPermissionLauncher: ActivityResultLauncher<Intent>,
    sharedModifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = getStrings(uiState.language)
    val changelog = uiState.updateChangelogMarkdown
    val context = LocalContext.current
    val view = LocalView.current
    val uriHandler = LocalUriHandler.current
    var whatsNewExpanded by remember { mutableStateOf(false) }
    val accent = OverlayThemeTokens.AccentNeonGreen
    val sheetInDarkTheme = isAppInDarkTheme()

    val sheetSurface = if (sheetInDarkTheme) {
        Color.Black.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    }

    val versionTag = uiState.latestVersion.orEmpty().ifBlank { "—" }
    val apkSizeLabel = formatApkSizeLabel(
        uiState.latestApkSizeBytes,
        uiState.language,
        strings.updateApkSizeUnit,
    ) ?: "—"
    val updatePitchText = strings.updateDialogDescription.format(versionTag, apkSizeLabel)

    val sheetHeadline = when {
        uiState.isUpdateChangelogLoading -> strings.checkForUpdateTitle
        uiState.updateStatus == AppUpdateStatus.UPDATE_AVAILABLE -> strings.updateDialogTitle
        else -> strings.checkForUpdateTitle
    }

    val whatsPreview = remember(changelog) {
        changelog?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.removePrefix("#")
            ?.trim()
            .orEmpty()
            .take(160)
    }
    val whatsSubtitle = when {
        whatsPreview.isNotBlank() -> whatsPreview
        else -> strings.updateWhatsNewFallback
    }

    val mdTypography = markdownTypography(
        h1 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        h2 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        h3 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        h4 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        h5 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        h6 = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        text = MaterialTheme.typography.bodySmall,
        paragraph = MaterialTheme.typography.bodySmall,
        bullet = MaterialTheme.typography.bodySmall,
        ordered = MaterialTheme.typography.bodySmall,
        list = MaterialTheme.typography.bodySmall,
    )

    val needsInstallPermission = !context.packageManager.canRequestPackageInstalls()
    val showInstallPermissionAction =
        uiState.updateStatus == AppUpdateStatus.UPDATE_AVAILABLE &&
            needsInstallPermission &&
            uiState.pendingApkPathForInstall != null &&
            !uiState.isApkDownloading

    val downloadProgressDisplayed by animateFloatAsState(
        targetValue = uiState.apkDownloadProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "apkSheetProgressDisplayed",
    )

    val sheetNestedShadowDp = OverlayThemeTokens.UpdateSheetNestedShadowElevation
    val starPlateShape = RoundedCornerShape(18.dp)
    val whatsNewShape = RoundedCornerShape(16.dp)

    Column(
        modifier = sharedModifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = IosDesign.SheetContentTop, bottom = 24.dp)
            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = {
                    performHaptic(view, Haptic.Light)
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = strings.updateCloseCd,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val starBg = if (sheetInDarkTheme) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .softPlateShadowForLightSheet(
                            sheetInDarkTheme,
                            starPlateShape,
                            sheetNestedShadowDp,
                        )
                        .clip(starPlateShape)
                        .background(starBg)
                        .then(
                            if (sheetInDarkTheme) {
                                Modifier
                            } else {
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    starPlateShape,
                                )
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = sheetHeadline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (uiState.isUpdateChangelogLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = accent)
            }
        } else {
            when (uiState.updateStatus) {
                AppUpdateStatus.UPDATE_AVAILABLE -> {
                    Text(
                        text = updatePitchText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = SnProFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                AppUpdateStatus.NO_UPDATE -> {
                    val installed = uiState.currentVersion.ifBlank { "—" }
                    val gh = uiState.latestVersion ?: "—"
                    Text(
                        text = strings.updateSheetNoUpdateDetail.format(installed, gh),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = SnProFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                AppUpdateStatus.ERROR -> {
                    Text(
                        text = uiState.updateChangelogError ?: strings.updateChangelogLoadError,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = SnProFamily,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                AppUpdateStatus.IDLE,
                AppUpdateStatus.LOADING,
                    -> {
                    Text(
                        text = strings.updateTileNewVersionSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = SnProFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                ),
        ) {
            val whatsNewBg = if (sheetInDarkTheme) {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            }
            val whatsNewInteractionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .softPlateShadowForLightSheet(
                        sheetInDarkTheme,
                        whatsNewShape,
                        sheetNestedShadowDp,
                    )
                    .clip(whatsNewShape)
                    .background(whatsNewBg, whatsNewShape)
                    .then(
                        if (sheetInDarkTheme) {
                            Modifier
                        } else {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                whatsNewShape,
                            )
                        },
                    )
                    .clickable(
                        interactionSource = whatsNewInteractionSource,
                        indication = ripple(bounded = true),
                        onClick = {
                            performHaptic(view, Haptic.Light)
                            whatsNewExpanded = !whatsNewExpanded
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Star,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.updateWhatsNewTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = whatsSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = SnProFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (whatsNewExpanded) Int.MAX_VALUE else 2,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (whatsNewExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!changelog.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                            Markdown(
                                content = changelog,
                                typography = mdTypography,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = strings.updateChangelogEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SnProFamily,
                        )
                    }
                }
            }
        }

        if (uiState.isApkDownloading) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { downloadProgressDisplayed.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                strokeCap = StrokeCap.Round,
            )
            val pct = (downloadProgressDisplayed * 100f).toInt().coerceIn(0, 100)
            Text(
                text = strings.updateDownloadProgressFormat.format(pct),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        if (!uiState.isUpdateChangelogLoading) {
            when (uiState.updateStatus) {
                AppUpdateStatus.UPDATE_AVAILABLE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                performHaptic(view, Haptic.Light)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(strings.updateInstallLater, fontFamily = SnProFamily)
                        }
                        Button(
                            onClick = {
                                performHaptic(view, Haptic.Light)
                                viewModel.startApkDownload(context)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isApkDownloading,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                strings.updateInstallNow,
                                fontFamily = SnProFamily,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                AppUpdateStatus.ERROR -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                performHaptic(view, Haptic.Light)
                                viewModel.loadUpdateChangelog(context)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(strings.updateRetryCheck, fontFamily = SnProFamily)
                        }
                        OutlinedButton(
                            onClick = {
                                performHaptic(view, Haptic.Light)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(strings.updateCloseCd, fontFamily = SnProFamily)
                        }
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = {
                            performHaptic(view, Haptic.Light)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(strings.updateCloseCd, fontFamily = SnProFamily)
                    }
                }
            }
        }

        if (showInstallPermissionAction) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    performHaptic(view, Haptic.Light)
                    installPermissionLauncher.launch(viewModel.manageUnknownAppSourcesIntent(context))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(strings.updateOpenInstallPermission, fontFamily = SnProFamily)
            }
        }
    }
}

@Composable
private fun ContactActionCard(
    @DrawableRes iconId: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardInDarkTheme = isAppInDarkTheme()
    val cardShape = RoundedCornerShape(20.dp)
    val cardBg = if (cardInDarkTheme) {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .aspectRatio(1.1f)
            .softPlateShadowForLightSheet(
                cardInDarkTheme,
                cardShape,
                OverlayThemeTokens.UpdateSheetNestedShadowElevation,
            )
            .clip(cardShape)
            .background(cardBg, cardShape)
            .then(
                if (cardInDarkTheme) {
                    Modifier
                } else {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        cardShape,
                    )
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = iconId),
                contentDescription = title,
                modifier = Modifier.size(36.dp),
                contentScale = ContentScale.Fit,
                colorFilter = when (iconId) {
                    R.drawable.ic_github -> ColorFilter.tint(
                        if (cardInDarkTheme) Color.White else Color.Black,
                    )
                    else -> null
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                ),
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
