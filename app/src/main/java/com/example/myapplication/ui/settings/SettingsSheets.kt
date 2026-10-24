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
