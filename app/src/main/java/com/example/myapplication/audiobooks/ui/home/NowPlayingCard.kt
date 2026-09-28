package com.example.myapplication.audiobooks.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.ContinueItem
import com.example.myapplication.audiobooks.ui.AudiobookPlayerState
import com.example.myapplication.audiobooks.ui.BookArt
import com.example.myapplication.audiobooks.ui.GlassCircleButton
import com.example.myapplication.audiobooks.ui.GlassSurface
import com.example.myapplication.audiobooks.ui.chapterPosition
import com.example.myapplication.audiobooks.ui.formatClock
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.phnem.vetro.R
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * «Продолжить» на доме — широкая карточка-плеер (референс пользователя): обложка на всю карточку,
 * стеклянная капсула автор/чтец, сердце, крупное название, время и три стеклянные кнопки. Если эта
 * книга уже в плеере, карточка показывает живой прогресс и управляет тем же плеером.
 *
 * Тап по самой карточке открывает полный плеер: книга в плеере — он просто разворачивается, иначе
 * [onOpen] запускает её сразу в полном виде. Кнопки внизу — управление без разворачивания.
 */
@Composable
internal fun NowPlayingCard(
    item: ContinueItem,
    strings: BooksHomeStrings,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player: AudiobookPlayerState = koinInject()
    val repository: AudiobookRepository = koinInject()
    val scope = rememberCoroutineScope()
    val favorite by remember(item.workId) { repository.isFavorite(item.workId) }.collectAsState(initial = false)
    val live = player.book?.takeIf { it.narrationId == item.narrationId }
    val isPlaying = live?.isPlaying == true

    // Прогресс книги: живой — из плеера, иначе — сохранённая позиция.
    val (elapsedMs, totalMs) = if (live != null) {
        val global = player.globalMs() ?: item.globalMs
        global to (player.timeline?.totalMs ?: item.totalMs)
    } else {
        item.globalMs to item.totalMs
    }
    val fraction = totalMs?.takeIf { it > 0 }?.let { elapsedMs.toFloat() / it } ?: 0f

    val shape = RoundedCornerShape(30.dp)
    val backdrop = rememberLayerBackdrop()
    Box(
        modifier
            .fillMaxWidth()
            .height(CardHeight)
            .shadow(16.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (live != null) player.requestExpand() else onOpen()
            },
    ) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            BookArt(item.coverUrl, Modifier.fillMaxSize())
            // Левая половина темнее — там текст и кнопки; справа обложка остаётся живой.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.78f),
                            0.55f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
        Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(backdrop, CircleShape, Modifier.height(CapsuleHeight).widthIn(max = 230.dp)) {
                Row(
                    Modifier.fillMaxHeight().padding(start = 5.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookArt(item.coverUrl, Modifier.size(34.dp).clip(CircleShape))
                    Spacer(Modifier.width(9.dp))
                    // Две строки обязаны уместиться в капсулу при любом масштабе шрифта: высота строк
                    // задана явно и без шрифтовых полей, масштаб ограничен — иначе чтец выпадал из
                    // капсулы вниз, прямо на название книги (как было в мини-плеере до той же правки).
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.1f)),
                    ) {
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(item.authors.joinToString(", ").ifBlank { item.title }, color = Color.White,
                                style = CapsuleLine.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 15.sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.narrators.isNotEmpty()) {
                                Text(item.narrators.joinToString(", "), color = Color.White.copy(alpha = 0.6f),
                                    style = CapsuleLine.copy(fontSize = 11.sp, lineHeight = 13.sp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            GlassCircleButton(
                backdrop, if (favorite) R.drawable.ph_heart_fill else R.drawable.ph_heart, strings.favorite,
                size = 44.dp, iconSize = 20.dp, tint = if (favorite) BrandOrange else Color.White,
            ) { scope.launch { repository.setFavorite(item.workId, !favorite) } }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 16.dp, bottom = 12.dp),
        ) {
            // Колонка прижата к низу: две строки по 23 sp заканчиваются ниже капсулы с запасом
            // (высота карточки подобрана под это), крупнее — название наезжало на капсулу.
            Text(item.title, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.Bold,
                fontSize = 20.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row {
                Text(formatClock(elapsedMs), color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                totalMs?.let {
                    Text("−" + formatClock((it - elapsedMs).coerceAtLeast(0)), color = Color.White,
                        fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassCircleButton(backdrop, R.drawable.ph_rewind_fill, "−15", 40.dp, 17.dp) {
                    if (live != null) player.seekBack() else onResume()
                }
                GlassCircleButton(
                    backdrop, if (isPlaying) R.drawable.ph_pause_fill else R.drawable.ph_play_fill, strings.resume, 46.dp, 21.dp,
                ) { if (live != null) player.playPause() else onResume() }
                GlassCircleButton(backdrop, R.drawable.ph_fast_forward_fill, "+30", 40.dp, 17.dp) {
                    if (live != null) player.seekForward() else onResume()
                }
            }
        }
    }
}

/** Высота карточки: капсула сверху (10 + 44 dp) и нижняя колонка с названием в две строки не пересекаются. */
private val CardHeight = 212.dp
private val CapsuleHeight = 44.dp

/** Строка капсулы без шрифтовых полей: высота строки — ровно lineHeight. */
private val CapsuleLine = TextStyle(
    fontFamily = SnProFamily,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    ),
)
