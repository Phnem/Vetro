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
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Phnem/Vetro")))
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
