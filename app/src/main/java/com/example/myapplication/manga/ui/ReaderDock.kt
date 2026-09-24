package com.example.myapplication.manga.ui

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.manga.data.ChapterReadingProgress
import com.example.myapplication.manga.data.MangaReaderMode
import com.example.myapplication.manga.domain.nextReaderLayout
import com.example.myapplication.manga.data.PageDirection
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.ui.shared.components.GrabberReservedTop
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Мини-док ридера: стеклянная капсула сверху справа с тремя переключателями — список глав,
 * раскладка чтения, обрезка полей.
 *
 * Материал тот же, что у шапок Details и дока на главном
 * ([com.example.myapplication.ui.shared.components.GlassMenuHeader]), но тона не из темы
 * приложения, а из [ambient] — по яркости страницы под доком (см. [rememberReaderAmbient]).
 * Зашитые тёмные тона тут не работают: страницы манги почти сплошь белые, и белые иконки на них
 * пропадали.
 *
 * @param backdrop слой со страницами под доком — его док преломляет.
 * @param ambient тон стекла и иконок под текущую страницу.
 */
@Composable
fun ReaderDock(
    backdrop: Backdrop,
    ambient: ReaderAmbient,
    mode: MangaReaderMode,
    direction: PageDirection,
    cropBorders: Boolean,
    ru: Boolean,
    onChapters: () -> Unit,
    onLayoutChange: (MangaReaderMode, PageDirection) -> Unit,
    onToggleCrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .height(DockHeight)
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(12f.dp.toPx())
                    lens(8f.dp.toPx(), 40f.dp.toPx())
                },
                onDrawSurface = { drawRect(ambient.tint) },
            )
            .border(0.5.dp, ambient.border, shape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockButton(
            icon = Icons.AutoMirrored.Filled.List,
            label = if (ru) "Главы" else "Chapters",
            contentColor = ambient.content,
            onClick = onChapters,
        )
        DockButton(
            // Иконка показывает текущую раскладку, а не следующую: стрелка совпадает с тем,
            // куда уезжает страница при свайпе.
            icon = layoutIcon(mode, direction),
            label = layoutLabel(mode, direction, ru),
            contentColor = ambient.content,
            onClick = {
                val (nextMode, nextDirection) = nextReaderLayout(mode, direction)
                onLayoutChange(nextMode, nextDirection)
            },
        )
        DockButton(
            icon = Icons.Filled.Crop,
            label = if (ru) "Обрезать поля" else "Crop borders",
            contentColor = ambient.content,
            active = cropBorders,
            onClick = onToggleCrop,
        )
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    contentColor: Color,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(DockButtonSize)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { performHaptic(view, "light"); onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            // Активное состояние остаётся брендовым в обоих тонах: оранжевый достаточно тёмный,
            // чтобы читаться и на белой странице, и на чёрной.
            tint = if (active) BrandOrange else contentColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Оглавление внутри ридера — содержимое для
 * [com.example.myapplication.ui.shared.components.IosSheetScaffold]. Тот же список, что открыл
 * ридер: за оглавлением в источник ридер не ходит принципиально (см. [MangaChapterHandoff]).
 */
@Composable
fun ReaderChaptersSheet(
    chapters: List<MangaChapter>,
    currentKey: String,
    progress: Map<String, ChapterReadingProgress>,
    ru: Boolean,
    onPick: (MangaChapter) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(chapters, currentKey) { chapters.indexOfFirst { it.key == currentKey } }

    // Открываем шторку сразу на читаемой главе: пролистывать до неё вручную в списке на сотни
    // глав — ровно та работа, ради которой оглавление и открывают.
    LaunchedEffect(currentIndex) {
        if (currentIndex > 0) listState.scrollToItem(currentIndex)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (ru) "Главы" else "Chapters",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                start = 20.dp,
                end = 20.dp,
                top = GrabberReservedTop + 8.dp,
                bottom = 8.dp,
            ),
        )
        LazyColumn(
            flingBehavior = IosScroll.flingBehavior(),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(chapters, key = { it.key }) { chapter ->
                ChapterSheetRow(
                    chapter = chapter,
                    current = chapter.key == currentKey,
                    progress = progress[chapter.key],
                    ru = ru,
                    onClick = { onPick(chapter) },
                )
            }
        }
    }
}

@Composable
private fun ChapterSheetRow(
    chapter: MangaChapter,
    current: Boolean,
    progress: ChapterReadingProgress?,
    ru: Boolean,
    onClick: () -> Unit,
) {
    val read = progress?.read == true
    val titleColor = when {
        current -> BrandOrange
        read -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SquircleShape(14.dp))
            .background(
                if (current) BrandOrange.copy(alpha = 0.12f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.readerTitle(ru),
                color = titleColor,
                fontSize = 14.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                chapter.language?.uppercase(),
                chapter.scanlator,
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Полоса только у начатых-недочитанных: у прочитанных её роль берёт приглушённый цвет.
            val fraction = progress?.fraction ?: 0f
            if (fraction > 0f && !read) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    color = BrandOrange,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
        }
        if (read) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = if (ru) "Прочитано" else "Read",
                tint = BrandOrange.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun layoutIcon(mode: MangaReaderMode, direction: PageDirection): ImageVector = when {
    mode == MangaReaderMode.Webtoon -> Icons.Filled.KeyboardDoubleArrowDown
    direction == PageDirection.Rtl -> Icons.Filled.KeyboardDoubleArrowLeft
    else -> Icons.Filled.KeyboardDoubleArrowRight
}

private fun layoutLabel(mode: MangaReaderMode, direction: PageDirection, ru: Boolean): String = when {
    mode == MangaReaderMode.Webtoon -> if (ru) "Вебтун" else "Webtoon"
    direction == PageDirection.Rtl -> if (ru) "Классика" else "Right to left"
    else -> if (ru) "Комикс" else "Left to right"
}

private val DockHeight = 44.dp
private val DockButtonSize = 38.dp
