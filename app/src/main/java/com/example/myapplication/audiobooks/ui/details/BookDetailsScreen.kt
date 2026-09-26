package com.example.myapplication.audiobooks.ui.details

import com.example.myapplication.audiobooks.domain.enrichment.BookWorkInfo
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.details.ActionButton
import com.example.myapplication.ui.details.DetailFact
import com.example.myapplication.ui.details.DetailFactGrid
import com.example.myapplication.ui.details.DetailsGlassBackButton
import com.example.myapplication.ui.details.MiniDockItem
import com.example.myapplication.ui.details.SectionHeader
import com.example.myapplication.ui.shared.GlassPreset
import com.example.myapplication.ui.shared.StatusBarIconsOverArt
import com.example.myapplication.ui.shared.adaptiveGlassBackdrop
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.rememberAdaptiveGlassEffects
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.phnem.vetro.R
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// ==========================================
// Страница книги (AB-31) — та же композиция, что Details аниме: hero-арт, лист со скруглённым
// верхом наезжает на него, действия, сетка фактов, секции; вторая страница — главы, между ними
// мини-док. Hero у книги — не растянутая портретная обложка (она мылится и режется, spec/09), а
// обложка по центру на размытой себе.
// ==========================================

@UnstableApi
@Composable
fun BookDetailsScreen(
    source: String,
    key: String,
    title: String,
    cover: String?,
    language: AppLanguage,
    onBack: () -> Unit,
    onOpenBook: (SourceBook) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookDetailsViewModel = koinViewModel(key = "book_$source/$key") { parametersOf(source, key, title) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val strings = remember(language) { bookDetailsStrings(language) }
    val isDark = isAppInDarkTheme()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 2 })
    val background = remember(isDark) { IosDesign.screenGradient(isDark) }
    val backdrop = rememberLayerBackdrop()
    var heroUnderStatusBar by remember { mutableStateOf(true) }
    val overArt by remember { derivedStateOf { pager.currentPage == 0 && heroUnderStatusBar } }
    StatusBarIconsOverArt(overArt)

    val book = state.details?.book
    val shownTitle = book?.title ?: title
    val shownCover = book?.coverUrl ?: cover ?: state.work?.coverUrl

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            HorizontalPager(
                state = pager,
                flingBehavior = IosScroll.pagerFlingBehavior(pager),
                overscrollEffect = null,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> InfoPage(
                        title = shownTitle,
                        cover = shownCover,
                        state = state,
                        favorite = favorite,
                        strings = strings,
                        isDark = isDark,
                        background = background,
                        onListen = { viewModel.listen() },
                        onChapters = { scope.launch { pager.animateScrollToPage(1, animationSpec = MotionTokens.sheetPresent()) } },
                        onFavorite = viewModel::toggleFavorite,
                        onNarration = viewModel::selectNarration,
                        onOpenBook = onOpenBook,
                        onRetry = viewModel::retry,
                        onHeroUnderStatusBar = { heroUnderStatusBar = it },
                    )
                    else -> ChaptersPage(
                        state = state,
                        strings = strings,
                        isDark = isDark,
                        background = background,
                        onChapter = { viewModel.listen(fromChapter = it) },
                    )
                }
            }
        }

        DetailsGlassBackButton(
            backdrop = backdrop,
            isDark = isDark,
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp)
                .zIndex(4f),
        )

        val dockEffects = rememberAdaptiveGlassEffects(GlassPreset.CompactNav)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .zIndex(4f)
                .height(56.dp)
                .clip(CircleShape)
                .adaptiveGlassBackdrop(backdrop = backdrop, shape = CircleShape, effects = dockEffects)
                .border(0.5.dp, if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f), CircleShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MiniDockItem(
                icon = ImageVector.vectorResource(R.drawable.ph_book_open),
                label = strings.details,
                selected = pager.targetPage == 0,
                isDark = isDark,
                onClick = { scope.launch { pager.animateScrollToPage(0, animationSpec = MotionTokens.sheetPresent()) } },
            )
            MiniDockItem(
                icon = ImageVector.vectorResource(R.drawable.ph_list_numbers),
                label = strings.chapters,
                selected = pager.targetPage == 1,
                isDark = isDark,
                onClick = { scope.launch { pager.animateScrollToPage(1, animationSpec = MotionTokens.sheetPresent()) } },
            )
        }
    }
}

// ---------- Страница «Детали» ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoPage(
    title: String,
    cover: String?,
    state: BookDetailsState,
    favorite: Boolean,
    strings: BookDetailsStrings,
    isDark: Boolean,
    background: Brush,
    onListen: () -> Unit,
    onChapters: () -> Unit,
    onFavorite: () -> Unit,
    onNarration: (SourceBook) -> Unit,
    onOpenBook: (SourceBook) -> Unit,
    onRetry: () -> Unit,
    onHeroUnderStatusBar: (Boolean) -> Unit,
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val details = state.details
    val heroHeight = 420.dp
    val overlap = 28.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val statusBarPx = WindowInsets.statusBars.getTop(density)
    val sheetTopPx = with(density) { (heroHeight - overlap).toPx() }
    val heroUnder by remember(statusBarPx, sheetTopPx) { derivedStateOf { sheetTopPx - scroll.value > statusBarPx } }
    val report by rememberUpdatedState(onHeroUnderStatusBar)
    LaunchedEffect(heroUnder) { report(heroUnder) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        // Hero: размытая обложка во всю ширину и та же обложка по центру — не растянутая.
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .graphicsLayer { translationY = -scroll.value * 0.35f },
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(40.dp),
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (isDark) 0.38f else 0.18f)))
            BookCoverImage(cover, title, Modifier.padding(bottom = overlap).size(188.dp, 282.dp), radius = 18.dp)
        }

        Column(Modifier.fillMaxSize().verticalScroll(scroll, flingBehavior = IosScroll.flingBehavior())) {
            Spacer(Modifier.height(heroHeight - overlap))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewport)
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(background)
                    .padding(horizontal = 20.dp),
            ) {
                GrabberHandle(isDark)
                Spacer(Modifier.height(6.dp))
                Text(title, color = onBg, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp,
                    lineHeight = 34.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                details?.book?.let { b ->
                    Spacer(Modifier.height(4.dp))
                    Text(b.authors.joinToString(", "), color = muted, fontFamily = SnProFamily, fontSize = 16.sp)
                }
                Spacer(Modifier.height(18.dp))

                // Действия: избранное / Слушать (Продолжить) / Главы.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FavoriteCircle(favorite, isDark, strings.favorite, onFavorite)
                    val resume = state.progress?.takeIf { !it.finished && it.globalMs > 0 } != null
                    ActionButton(
                        label = if (resume) strings.resume else strings.listen,
                        icon = ImageVector.vectorResource(R.drawable.ph_play_fill),
                        bg = BrandOrange,
                        fg = Color.White,
                        onClick = onListen,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        label = strings.chapters,
                        icon = ImageVector.vectorResource(R.drawable.ph_list_dashes),
                        bg = if (isDark) Color.White else Color.Black,
                        fg = if (isDark) Color.Black else Color.White,
                        onClick = onChapters,
                        modifier = Modifier.weight(1f),
                    )
                }
                when {
                    state.launching -> StatusLine(strings.loading, spinner = true)
                    state.launchFailed -> StatusLine(strings.launchFailed)
                }
                // Прогресс этой озвучки, если её уже слушали.
                val total = details?.chapterDurationsSec?.takeIf { d -> d.all { it != null } }?.sumOf { it!! }?.times(1000)
                val progress = state.progress
                if (progress != null && total != null && total > 0 && progress.globalMs > 0) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(onBg.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth((progress.globalMs.toFloat() / total).coerceIn(0f, 1f)).height(4.dp).background(BrandOrange))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(strings.left(strings.formatDuration((total - progress.globalMs).coerceAtLeast(0) / 1000)),
                        color = muted, fontFamily = SnProFamily, fontSize = 13.sp)
                }
                Spacer(Modifier.height(24.dp))

                when {
                    details != null -> {
                        val facts = buildFacts(details, state.work, state.narrations.size, state.sourceName, strings)
                        SectionHeader(strings.information, onBg)
                        Spacer(Modifier.height(12.dp))
                        DetailFactGrid(facts, isDark)
                        Spacer(Modifier.height(24.dp))

                        if (state.narrations.size > 1) {
                            SectionHeader(strings.narrations, onBg)
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.narrations.forEach { n ->
                                    NarrationChip(
                                        text = listOfNotNull(
                                            n.narrators.joinToString(", ").ifBlank { null },
                                            n.durationSec?.let { strings.formatDuration(it) },
                                        ).joinToString(" · "),
                                        selected = n.ref.key == state.key && n.ref.source.value == state.source,
                                        isDark = isDark,
                                    ) { onNarration(n) }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }

                        (details.description?.takeIf { it.isNotBlank() } ?: state.work?.description)?.let { text ->
                            var expanded by remember(state.key) { mutableStateOf(false) }
                            SectionHeader(strings.description, onBg)
                            Spacer(Modifier.height(10.dp))
                            Text(text, color = muted, fontFamily = SnProFamily, fontSize = 14.sp, lineHeight = 22.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (expanded) strings.showLess else strings.showMore,
                                color = onBg, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(onBg.copy(alpha = 0.08f))
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                            Spacer(Modifier.height(24.dp))
                        }

                        if (state.authorBooks.isNotEmpty()) {
                            SectionHeader(strings.moreByAuthor, onBg)
                            Spacer(Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.authorBooks, key = { it.ref.key }) { b ->
                                    Column(Modifier.width(112.dp).clickable { onOpenBook(b) }) {
                                        BookCoverImage(b.coverUrl, b.title, Modifier.size(112.dp, 168.dp), radius = 12.dp)
                                        Spacer(Modifier.height(6.dp))
                                        Text(b.title, color = onBg, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    state.loading -> StatusLine(strings.loading, spinner = true)
                    state.restricted -> StatusLine(strings.restricted)
                    else -> {
                        StatusLine(strings.loadFailed)
                        Spacer(Modifier.height(10.dp))
                        Text(strings.retry, color = BrandOrange, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onRetry))
                    }
                }
                Spacer(Modifier.height(140.dp)) // запас под мини-док
            }
        }
    }
}

/** Сетка фактов: только те, что источник действительно знает — без «—» (как у аниме). */
@Composable
private fun buildFacts(
    details: SourceBookDetails,
    work: BookWorkInfo?,
    narrations: Int,
    sourceName: String,
    s: BookDetailsStrings,
): List<DetailFact> {
    val b = details.book
    val icons = mapOf(
        "rating" to ImageVector.vectorResource(R.drawable.ph_star),
        "duration" to ImageVector.vectorResource(R.drawable.ph_clock),
        "chapters" to ImageVector.vectorResource(R.drawable.ph_list_numbers),
        "year" to ImageVector.vectorResource(R.drawable.ph_calendar_blank),
        "genre" to ImageVector.vectorResource(R.drawable.ph_tag),
        "narrator" to ImageVector.vectorResource(R.drawable.ph_microphone),
        "author" to ImageVector.vectorResource(R.drawable.ph_user),
        "series" to ImageVector.vectorResource(R.drawable.ph_books),
        "narrations" to ImageVector.vectorResource(R.drawable.ph_waveform),
        "source" to ImageVector.vectorResource(R.drawable.ph_headphones),
    )
    val total = details.chapterDurationsSec.takeIf { d -> d.isNotEmpty() && d.all { it != null } }?.sumOf { it!! } ?: b.durationSec
    return listOfNotNull(
        details.rating?.let { DetailFact("rating", icons.getValue("rating"), s.rating, "%.1f / 5".format(it)) },
        total?.let { DetailFact("duration", icons.getValue("duration"), s.duration, s.formatDuration(it)) },
        details.chapterDurationsSec.size.takeIf { it > 0 }?.let { DetailFact("chapters", icons.getValue("chapters"), s.chapterCount, s.chaptersN(it)) },
        b.year?.let { DetailFact("year", icons.getValue("year"), s.year, it.toString()) },
        b.genres.firstOrNull()?.let { DetailFact("genre", icons.getValue("genre"), s.genre, it) },
        b.narrators.takeIf { it.isNotEmpty() }?.let { DetailFact("narrator", icons.getValue("narrator"), s.narrator, it.joinToString(", ")) },
        b.authors.takeIf { it.isNotEmpty() }?.let { DetailFact("author", icons.getValue("author"), s.author, it.joinToString(", ")) },
        details.series?.let { DetailFact("series", icons.getValue("series"), s.series, it) },
        // Произведение за озвучкой: как оно называется в оригинале и когда вышло впервые.
        work?.originalTitle?.let { DetailFact("original", icons.getValue("series"), s.original, it) },
        work?.firstPublishYear?.takeIf { it != b.year }?.let {
            DetailFact("firstPublished", icons.getValue("year"), s.firstPublished, it.toString())
        },
        narrations.takeIf { it > 1 }?.let { DetailFact("narrations", icons.getValue("narrations"), s.narrationCount, s.narrationsN(it)) },
        sourceName.takeIf { it.isNotBlank() }?.let { DetailFact("source", icons.getValue("source"), s.source, it) },
    )
}

// ---------- Страница «Главы» (вид оглавления манги) ----------

@Composable
private fun ChaptersPage(
    state: BookDetailsState,
    strings: BookDetailsStrings,
    isDark: Boolean,
    background: Brush,
    onChapter: (Int) -> Unit,
) {
    val details = state.details
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().background(background).statusBarsPadding().padding(top = 72.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(details?.book?.title.orEmpty(), color = onBg, fontFamily = SnProFamily, fontWeight = FontWeight.Bold,
                fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                details?.let { d ->
                    MetaPill(strings.chaptersN(d.chapterDurationsSec.size), isDark)
                    d.chapterDurationsSec.takeIf { l -> l.all { it != null } }?.sumOf { it!! }?.let { MetaPill(strings.formatDuration(it), isDark) }
                    d.book.narrators.firstOrNull()?.let { MetaPill(it, isDark) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val durations = details?.chapterDurationsSec.orEmpty()
        // Текущая глава — по сохранённой позиции этой озвучки.
        val current = state.progress?.let { p ->
            var acc = 0L
            durations.indexOfFirst { d -> acc += (d ?: 0L) * 1000; p.globalMs < acc }.takeIf { it >= 0 }
        }
        LazyColumn(
            flingBehavior = IosScroll.flingBehavior(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(durations) { i, sec ->
                val isCurrent = i == current
                val listened = current != null && i < current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(rowShape(i, durations.size))
                        .background(if (isDark) Color.Black.copy(alpha = if (isCurrent) 0.34f else 0.22f) else Color.White.copy(alpha = 0.72f))
                        .clickable { onChapter(i) }
                        .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.chapterN(i + 1), color = if (listened) muted else onBg, fontFamily = SnProFamily,
                            fontWeight = if (listened) FontWeight.Medium else FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!listened) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(BrandOrange))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                listOfNotNull(sec?.let { strings.formatDuration(it) }, if (isCurrent) strings.nowListening else null).joinToString(" • "),
                                color = muted, fontFamily = SnProFamily, fontSize = 11.sp,
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(if (isCurrent) R.drawable.ph_waveform else if (listened) R.drawable.ph_check else R.drawable.ph_play_fill),
                        contentDescription = null,
                        tint = when {
                            isCurrent -> BrandOrange
                            listened -> muted
                            else -> muted.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun rowShape(index: Int, size: Int): Shape {
    val top = if (index == 0) 22.dp else 6.dp
    val bottom = if (index == size - 1) 22.dp else 6.dp
    return SquircleCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

// ---------- Мелочи ----------

@Composable
private fun BookCoverImage(url: String?, title: String, modifier: Modifier, radius: Dp) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .shadow(14.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(title, color = Color.White.copy(alpha = 0.72f), fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun FavoriteCircle(favorite: Boolean, isDark: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (favorite) BrandOrange else if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f))
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (favorite) R.drawable.ph_heart_fill else R.drawable.ph_heart),
            contentDescription = description,
            tint = if (favorite) Color.White else BrandOrange,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun NarrationChip(text: String, selected: Boolean, isDark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) BrandOrange else if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ph_microphone), null, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun MetaPill(text: String, isDark: Boolean) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = SnProFamily, fontSize = 12.sp,
        fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f))
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun StatusLine(text: String, spinner: Boolean = false) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spinner) {
            CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = SnProFamily, fontSize = 14.sp)
    }
}
