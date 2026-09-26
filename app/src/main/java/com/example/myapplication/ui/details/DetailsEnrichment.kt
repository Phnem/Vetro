package com.example.myapplication.ui.details

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.domain.enrichment.title.NextRelease
import com.example.myapplication.domain.enrichment.title.ReleaseConfidence
import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.VideoClip
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
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
) {
    var logoReady by remember(logo?.url) { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(if (logoReady) 1f else 0f, MotionTokens.standard(), label = "detailsLogoAlpha")
    Box {
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
 * «Следующая серия через 4 дн 16 ч 33 мин 00 с»: тикает раз в секунду, пока карточка на экране.
 * Время — показа в стране производства; для оценки по ритму выхода — со знаком «≈»; если известна
 * только дата — дата, без отсчёта.
 */
@Composable
internal fun NextReleaseCard(release: NextRelease, ru: Boolean, isDark: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val at = release.at
    if (at != null) {
        LaunchedEffect(at) {
            while (true) {
                now = System.currentTimeMillis()
                if (now >= at.toEpochMilli()) break
                delay(1_000L - now % 1_000L)
            }
        }
    }
    val value = releaseText(release, Instant.ofEpochMilli(now), ru)
    val caption = when {
        release.confidence == ReleaseConfidence.ESTIMATED -> if (ru) "По ритму выхода, время показа оригинала" else "By weekly rhythm, original airing"
        else -> if (ru) "Время показа оригинала" else "Original airing"
    }
    val label = listOfNotNull(
        if (ru) "Следующая серия" else "Next episode",
        release.episode?.let { if (ru) "№$it" else "#$it" },
    ).joinToString(" ")
    val cardBg = if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f)
    val wellBg = if (isDark) Color.Black.copy(alpha = 0.55f) else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SquircleShape(26.dp))
            .background(cardBg)
            .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
            .semantics { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(wellBg), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.06.em),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = value,
                // Цифры одной ширины — строка отсчёта не дрожит каждую секунду.
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Пилюля «Трейлер»: открывает ролик в YouTube (приложение или браузер). */
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
                val url = "https://www.youtube.com/watch?v=${trailer.key}"
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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

/**
 * Текст срока. Отсчёт — дни, часы, минуты, секунды (секунды двумя знаками); прошло — «выходит сейчас».
 */
internal fun releaseText(release: NextRelease, now: Instant, ru: Boolean): String {
    val at = release.at
    if (at == null || release.confidence == ReleaseConfidence.DATE_ONLY) {
        val date = release.date ?: return if (ru) "Скоро" else "Soon"
        val pattern = DateTimeFormatter.ofPattern(if (ru) "d MMM" else "MMM d", if (ru) Locale.forLanguageTag("ru") else Locale.ENGLISH)
        return date.format(pattern)
    }
    val left = Duration.between(now, at)
    if (left.isNegative || left.isZero) return if (ru) "Выходит сейчас" else "Airing now"
    val d = left.toDays()
    val h = left.toHours() % 24
    val m = left.toMinutes() % 60
    val s = left.seconds % 60
    val prefix = if (release.confidence == ReleaseConfidence.ESTIMATED) "≈ " else ""
    val parts = if (ru) {
        listOfNotNull("$d дн".takeIf { d > 0 }, "$h ч".takeIf { d > 0 || h > 0 }, "$m мин", "%02d с".format(s))
    } else {
        listOfNotNull("${d}d".takeIf { d > 0 }, "${h}h".takeIf { d > 0 || h > 0 }, "${m}m", "%02ds".format(s))
    }
    return prefix + (if (ru) "через " else "in ") + parts.joinToString(" ")
}

private val LogoHeight = 64.dp
