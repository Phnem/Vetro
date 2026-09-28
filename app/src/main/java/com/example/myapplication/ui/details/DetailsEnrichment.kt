package com.example.myapplication.ui.details

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.domain.enrichment.title.NextRelease
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.domain.enrichment.title.countdownText
import com.example.myapplication.domain.enrichment.title.runCountdown
import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.VideoClip
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import java.time.Duration
import kotlinx.coroutines.delay

/**
 * Название тайтла: прозрачный логотип, если он есть и загрузился, иначе текст. Пока логотип
 * грузится или если не загрузился — стоит текст, поэтому шапка не прыгает и не пустеет.
 */
@Composable
internal fun DetailsTitle(
    displayTitle: String,
    logo: ArtworkImage?,
    textStyle: TextStyle,
    color: Color,
    /** Подпись тоста «скопировано» (до Android 13 — там система своего не показывает). */
    copiedLabel: String = "Название скопировано",
) {
    var logoReady by remember(logo?.url) { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(if (logoReady) 1f else 0f, MotionTokens.standard(), label = "detailsLogoAlpha")
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    // Удержание названия (и текста, и логотипа) копирует его — чтобы искать тайтл где-то ещё.
    Box(
        Modifier.pointerInput(displayTitle) {
            detectTapGestures(onLongPress = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(displayTitle, displayTitle))
                com.example.myapplication.utils.performHaptic(view, com.example.myapplication.utils.Haptic.Light)
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.widget.Toast.makeText(context, copiedLabel, android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        },
    ) {
        if (!logoReady) {
            Text(text = displayTitle, style = textStyle, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (logo != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(logo.url).crossfade(false).build(),
                contentDescription = displayTitle,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onSuccess = { logoReady = true },
                onError = { logoReady = false },
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(LogoHeight)
                    .graphicsLayer { alpha = logoAlpha },
            )
        }
    }
}

/**
 * «Следующий эпизод через:» — обычная секция Details, без карточки и подложки. Тикает раз в секунду
 * по часам устройства; время вышло — секция исчезает, отрицательных значений нет, а [onElapsed]
 * просит перечитать расписание.
 */
@Composable
internal fun NextReleaseSection(release: NextRelease, ru: Boolean, onElapsed: () -> Unit) {
    var remaining by remember(release.at) {
        mutableStateOf<Duration?>(Duration.ofMillis(release.at.toEpochMilli() - System.currentTimeMillis()))
    }
    val elapsed by rememberUpdatedState(onElapsed)
    LaunchedEffect(release.at) {
        runCountdown(release.at, System::currentTimeMillis, { delay(it) }) { remaining = it }
        remaining = null
        elapsed()
    }
    val value = remaining?.let { countdownText(it, ru) } ?: return
    // Эфир оригинала вместо трека языка — подписываем, чтобы «через 2 дня» не приняли за озвучку.
    val label = when {
        !release.original -> if (ru) "Следующий эпизод через:" else "Next episode in:"
        release.source == EnrichmentSource.ANILIST -> if (ru) "Следующий эпизод в Японии через:" else "Next episode airs in Japan in:"
        else -> if (ru) "Следующий эпизод в оригинале через:" else "Next episode (original airing) in:"
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label $value" }) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            // Цифры одной ширины — строка не дрожит каждую секунду.
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
    }
}

/**
 * Открыть ролик в приложении YouTube. Обычная https-ссылка на многих прошивках уходит в браузер
 * (и тот открывает десктопную версию), поэтому сначала — схема приложения и явный пакет, затем
 * мобильный сайт.
 */
internal fun openYouTubeVideo(context: android.content.Context, videoId: String) {
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            .setPackage("com.google.android.youtube"),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com/watch?v=$videoId")),
    )
    for (intent in attempts) {
        val started = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (started) return
    }
}

/** Пилюля «Трейлер»: открывает ролик в приложении YouTube. */
@Composable
internal fun TrailerPill(trailer: VideoClip, ru: Boolean, isDark: Boolean) {
    val context = LocalContext.current
    val onBg = MaterialTheme.colorScheme.onBackground
    val pillBg = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(pillBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                openYouTubeVideo(context, trailer.key)
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (ru) "Трейлер" else "Trailer",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold),
            color = onBg,
        )
    }
}

private val LogoHeight = 64.dp
