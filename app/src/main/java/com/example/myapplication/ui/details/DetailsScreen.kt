package com.example.myapplication.ui.details

import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.glassControl
import com.example.myapplication.ui.shared.StatusBarIconsOverArt
import androidx.compose.ui.platform.LocalDensity
import com.example.myapplication.ui.shared.LocalModernUi
import android.graphics.BitmapFactory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.domain.seasons.shortLabel
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.manga.ui.MangaChaptersPage
import com.example.myapplication.manga.ui.rememberMangaContinueReading
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.GlassPreset
import com.example.myapplication.ui.shared.adaptiveGlassBackdrop
import com.example.myapplication.ui.shared.rememberAdaptiveGlassEffects
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Полноэкранные детали тайтла — NavHost destination (DetailsRoute). Две страницы в
 * [HorizontalPager] со свайпом влево/вправо: «Детали» (постер-hero, мета, описание) и
 * «Серии» (сезоны и серии со стримом и загрузкой, см. [ModernDetailsEpisodesPage]).
 * Внизу — стеклянный мини-док в духе главного дока, но компактный: активная вкладка
 * раскрывается с подписью, неактивная сворачивается до иконки.
 */
@Composable
fun DetailsScreen(
    navController: NavController,
    animeId: String,
    openEpisodes: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = koinViewModel(key = animeId) { parametersOf(animeId) },
) {
    val anime by viewModel.currentAnime.collectAsStateWithLifecycle()
    val language by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val webLinks by viewModel.webLinks.collectAsStateWithLifecycle()
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()
    val enrichment by viewModel.enrichment.collectAsStateWithLifecycle()
    val isDark = isAppInDarkTheme()

    val current = anime
    if (current == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    val screenBg = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(screenBg)
        drawContent()
    }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pagerState = rememberPagerState(
        initialPage = if (openEpisodes) 1 else 0,
        pageCount = { 2 },
    )
    // Значки статус-бара белые, пока под ним арт страницы «Детали»; на странице серий и когда
    // лист наехал на арт — по теме.
    var heroUnderStatusBar by remember { mutableStateOf(true) }
    val statusBarOverArt by remember {
        derivedStateOf { pagerState.currentPage == 0 && heroUnderStatusBar }
    }
    StatusBarIconsOverArt(statusBarOverArt)

    val displayTitle = when (language) {
        AppLanguage.EN -> current.titleEn?.takeIf { it.isNotBlank() } ?: current.title
        AppLanguage.RU -> current.titleRu?.takeIf { it.isNotBlank() } ?: current.title
    }
    val episodeAnime = remember(current, displayTitle) {
        current.copy(title = displayTitle)
    }
    // У манги вторая страница — главы из движка манги, а не серии: разный источник и разный ридер.
    val isManga = current.mediaType == MediaType.MANGA
    // У манги вторая страница — главы, серийный VM ей не нужен: раньше он создавался и для неё,
    // сканировал «скачанные серии» и грузил обложки серий впустую.
    val episodeMenuViewModel: EpisodeMenuViewModel? = if (isManga) {
        null
    } else {
        koinViewModel(key = "episode_menu_${current.id}") { parametersOf(episodeAnime) }
    }


    fun openEpisodes() {
        scope.launch {
            pagerState.animateScrollToPage(1, animationSpec = MotionTokens.sheetPresent())
        }
    }

    Box(modifier = modifier.fillMaxSize().background(screenBg)) {
        // Обе страницы лежат под layerBackdrop — стеклянные кнопка «назад» и мини-док
        // преломляют живой контент. Узел с layerBackdrop никогда не размонтируется.
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            HorizontalPager(
                flingBehavior = IosScroll.pagerFlingBehavior(pagerState),
                // Резинка на краях у страниц читается как дёрганье (см. IosScroll.pagerFlingBehavior).
                overscrollEffect = null,
                state = pagerState,
                // Свайп вправо со страницы «Детали» — назад на главную (как в iOS, от любой точки).
                modifier = Modifier.fillMaxSize().detailsSwipeBack(pagerState, enabled = true),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> DetailsInfoPage(
                        anime = current,
                        displayTitle = displayTitle,
                        uiState = uiState,
                        language = language,
                        isDark = isDark,
                        imgPath = viewModel.getImgPath(current.imageFileName),
                        webLinks = webLinks,
                        seasons = seasons,
                        enrichment = enrichment,
                        onReleaseElapsed = viewModel::onReleaseElapsed,
                        onWatch = {
                            performHaptic(view, Haptic.Light)
                            openEpisodes()
                        },
                        onDownload = {
                            performHaptic(view, Haptic.Light)
                            openEpisodes()
                        },
                        onToggleFavorite = {
                            performHaptic(view, if (current.isFavorite) Haptic.Light else Haptic.Success)
                            viewModel.toggleFavorite()
                        },
                        onOpenSeason = {
                            performHaptic(view, Haptic.Light)
                            openEpisodes()
                        },
                        onHeroUnderStatusBar = { heroUnderStatusBar = it },
                    )

                    else -> if (isManga) MangaChaptersPage(
                        animeId = current.id,
                        animeTitle = displayTitle,
                        animeTitleEn = current.titleEn,
                        language = language,
                        // Отступ под шапку страница накладывает сама — на СОДЕРЖИМОЕ, не на фон.
                        // Снаружи он ужимал и фон тоже, и над градиентом оставалась чёрная полоса
                        // корневого экрана.
                        modifier = Modifier.fillMaxSize(),
                    ) else ModernDetailsEpisodesPage(
                        animeId = current.id,
                        animeTitle = displayTitle,
                        animeTitleRu = current.titleRu,
                        animeTitleEn = current.titleEn,
                        malId = current.malId,
                        anilistId = current.anilistId,
                        language = language,
                        isDark = isDark,
                        seasons = seasons,
                        fallbackEpisodes = current.episodes,
                        posterPath = viewModel.getImgPath(current.imageFileName),
                        viewModel = checkNotNull(episodeMenuViewModel),
                    )
                }
            }
        }

        DetailsGlassBackButton(
            backdrop = backdrop,
            isDark = isDark,
            onClick = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp)
                .zIndex(4f),
        )

        DetailsMiniDock(
            backdrop = backdrop,
            isDark = isDark,
            language = language,
            isManga = isManga,
            activePage = pagerState.targetPage,
            onSelect = { page ->
                performHaptic(view, Haptic.Light)
                scope.launch {
                    pagerState.animateScrollToPage(page, animationSpec = MotionTokens.sheetPresent())
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .zIndex(4f),
        )

        // Кнопка «продолжить» относится к списку серий/глав, поэтому живёт только на второй
        // странице. У манги она делает то же самое, что у аниме, — открывает то, на чём читатель
        // остановился; у манги вдобавок может быть нечего открывать (источник ещё не привязан),
        // и тогда кнопки просто нет.
        if (pagerState.targetPage == 1) {
            val startButtonModifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .offset(x = 124.dp)
                .zIndex(4f)
            if (isManga) {
                val continueReading = rememberMangaContinueReading(
                    animeId = current.id,
                    animeTitle = displayTitle,
                    animeTitleEn = current.titleEn,
                )
                if (continueReading != null) {
                    DetailsGlassStartButton(
                        backdrop = backdrop,
                        onClick = {
                            performHaptic(view, Haptic.Light)
                            continueReading()
                        },
                        modifier = startButtonModifier,
                    )
                }
            } else {
                DetailsGlassStartButton(
                    backdrop = backdrop,
                    onClick = {
                        performHaptic(view, Haptic.Light)
                        episodeMenuViewModel?.startWatching()
                    },
                    modifier = startButtonModifier,
                )
            }
        }
    }
}

// ==========================================
// Страница 1: инфо о тайтле
// ==========================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsInfoPage(
    anime: Anime,
    displayTitle: String,
    uiState: DetailsUiState,
    language: AppLanguage,
    isDark: Boolean,
    imgPath: String?,
    webLinks: List<com.example.myapplication.domain.enrichment.weblinks.ResolvedWebLink>,
    seasons: List<com.example.myapplication.domain.seasons.SeasonInfo>,
    enrichment: com.example.myapplication.domain.enrichment.title.TitleEnrichment?,
    onReleaseElapsed: () -> Unit,
    onWatch: () -> Unit,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSeason: () -> Unit,
    onHeroUnderStatusBar: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val details = (uiState as? DetailsUiState.Success)?.details
    var descriptionExpanded by remember { mutableStateOf(false) }

    val screenBg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val chipBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val ru = language == AppLanguage.RU
    val posterAccent = rememberPosterAccent(imgPath)
    // Лист непрозрачный на всей высоте. Раньше верхние стопы шли с alpha < 1, из-за чего сквозь
    // начало листа просвечивал hero и была видна граница обложки. Переход к фону экрана делаем
    // смешиванием цветов, а не прозрачностью.
    val detailsBackground = remember(posterAccent, screenBg, isDark) {
        val leading = if (isDark) {
            lerp(posterAccent, Color.Black, 0.48f)
        } else {
            lerp(posterAccent, Color.White, 0.68f)
        }
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to leading,
                0.30f to lerp(leading, screenBg, if (isDark) 0.18f else 0.28f),
                0.68f to screenBg,
                1f to screenBg,
            ),
        )
    }

    // Две зоны: hero-арт («К») закреплён за контентом, а лист контента («Ж») со скруглённым
    // верхом лежит поверх и при скролле наезжает на арт (и съезжает обратно). Сам арт при
    // этом чуть уезжает вверх с параллаксом — наезд читается объёмнее.
    val heroHeight = 400.dp
    val sheetOverlap = 28.dp
    val sheetCorner = 30.dp
    val scrollState = rememberScrollState()

    // Арт под статус-баром, пока верх листа ниже статус-бара. Меняется один раз за прокрутку.
    val density = LocalDensity.current
    val statusBarPx = WindowInsets.statusBars.getTop(density)
    val sheetTopPx = with(density) { (heroHeight - sheetOverlap).toPx() }
    val heroUnderStatusBar by remember(statusBarPx, sheetTopPx) {
        derivedStateOf { sheetTopPx - scrollState.value > statusBarPx }
    }
    val reportHero by rememberUpdatedState(onHeroUnderStatusBar)
    LaunchedEffect(heroUnderStatusBar) { reportHero(heroUnderStatusBar) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight

        // ——— «К»: закреплённый hero-арт под листом ———
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .graphicsLayer { translationY = -scrollState.value * 0.35f },
        ) {
            if (imgPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(imgPath))
                        .crossfade(true)
                        .build(),
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isDark) Color(0xFF161616) else Color(0xFFE4E4E8)),
                )
            }
            // Скрим сверху — под статус-баром и кнопкой «назад» арт всегда читается.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.38f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }

        // ——— «Ж»: лист контента со скруглённым верхом, скользит поверх арта ———
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState, flingBehavior = IosScroll.flingBehavior()),
        ) {
            // Прозрачное окно, сквозь которое виден hero; дальше начинается сам лист.
            Spacer(Modifier.height(heroHeight - sheetOverlap))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Лист не короче вьюпорта: даже при пустом описании он может
                    // полностью наехать на hero при прокрутке.
                    .heightIn(min = viewportHeight)
                    .clip(RoundedCornerShape(topStart = sheetCorner, topEnd = sheetCorner))
                    .background(detailsBackground)
                    .padding(horizontal = 20.dp),
            ) {
            // Grab-индикатор как у обычной шторки: сразу читается, что лист «наезжает» на арт.
            GrabberHandle(isDark)
            Spacer(Modifier.height(6.dp))
            // Прозрачный логотип тайтла (TMDb / Fanart.tv) вместо текста, если он есть и загрузился.
            DetailsTitle(
                displayTitle = displayTitle,
                logo = enrichment?.logo,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    lineBreak = LineBreak.Heading,
                    hyphens = Hyphens.Auto,
                ),
                color = onBg,
            )

            // Строки мета-чипов «дата · рейтинг · серии» здесь больше нет: дата дублировала
            // карточку «Релиз», число серий — карточку «Эпизоды», а рейтинг переехал в сетку
            // фактов отдельной карточкой (см. buildDetailFacts, ключ "rating").

            Spacer(Modifier.height(18.dp))

            // ——— Действия: закладка / Смотреть / Скачать → обе пилюли ведут на страницу серий ———
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FavoriteButton(
                    isFavorite = anime.isFavorite,
                    isDark = isDark,
                    ru = ru,
                    onClick = onToggleFavorite,
                )
                ActionButton(
                    label = if (ru) "Смотреть" else "Watch",
                    icon = Icons.Rounded.PlayArrow,
                    bg = MaterialTheme.colorScheme.primary,
                    fg = Color.White,
                    onClick = onWatch,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = if (ru) "Скачать" else "Download",
                    icon = Icons.Rounded.Download,
                    bg = if (isDark) Color.White else Color.Black,
                    fg = if (isDark) Color.Black else Color.White,
                    onClick = onDownload,
                    modifier = Modifier.weight(1f),
                )
            }

            // ——— Трейлер (слой обогащения) — рядом с действиями ———
            enrichment?.trailer?.let { trailer ->
                Spacer(Modifier.height(14.dp))
                TrailerPill(trailer = trailer, ru = ru, isDark = isDark)
            }

            // ——— Где смотреть: стопка иконок найденных сайтов ———
            if (webLinks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                com.example.myapplication.ui.shared.components.WebLinkChipStack(
                    links = webLinks,
                    language = language,
                    chipSize = 34.dp,
                    maxVisible = 6,
                    ringColor = screenBg,
                )
            }

            // ——— Следующий эпизод через: трек языка интерфейса; время вышло — перечитать расписание ———
            enrichment?.nextRelease?.let { release ->
                Spacer(Modifier.height(20.dp))
                NextReleaseSection(release = release, ru = ru, onElapsed = onReleaseElapsed)
            }

            Spacer(Modifier.height(24.dp))

            // ——— Информация: сетка 2×N. Пока карточка API не пришла, факты берутся из
            // локальной записи (счётчик серий), поэтому секция не мигает пустотой. ———
            val ratings = enrichment?.ratings
            val facts = remember(anime, details, ru, ratings) { buildDetailFacts(anime, details, ru, ratings) }
            if (facts.isNotEmpty()) {
                SectionHeader(text = if (ru) "Информация" else "Information", color = onBg)
                Spacer(Modifier.height(12.dp))
                DetailFactGrid(facts = facts, isDark = isDark)
                Spacer(Modifier.height(24.dp))
            }

            // ——— Жанры ———
            val genres = details?.genres?.takeIf { it.isNotEmpty() }
                ?: anime.tags.takeIf { it.isNotEmpty() }
            if (!genres.isNullOrEmpty()) {
                SectionHeader(text = if (ru) "Жанры" else "Genres", color = onBg)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    genres.forEach { genre ->
                        MetaChip(text = genre, chipBg = chipBg, fg = muted)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // ——— Русская озвучка: какие студии озвучивают (Shikimori) ———
            val dubs = enrichment?.russianDubs.orEmpty()
            if (ru && dubs.isNotEmpty()) {
                SectionHeader(text = "Русская озвучка", color = onBg)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dubs.take(12).forEach { studio -> MetaChip(text = studio, chipBg = chipBg, fg = muted) }
                }
                Spacer(Modifier.height(24.dp))
            }

            // ——— Сезоны: число серий каждого сезона (фоновый резолв AniList→Shikimori→MAL) ———
            if (seasons.isNotEmpty()) {
                SectionHeader(text = if (ru) "Сезоны" else "Seasons", color = onBg)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { season ->
                        SeasonChip(season = season, ru = ru, onClick = onOpenSeason)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // ——— Описание ———
            when (uiState) {
                is DetailsUiState.Idle, DetailsUiState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = if (ru) "Загрузка..." else "Loading...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                            color = muted,
                        )
                    }
                }

                is DetailsUiState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (ru) "Не удалось загрузить" else "Could not load details",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                            color = muted,
                        )
                    }
                }

                is DetailsUiState.MissingEnglishTitle -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = muted,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (ru) {
                                "Описание недоступно. Добавьте английское название или привяжите MAL/AniList id для более точной загрузки."
                            } else {
                                "Description unavailable. Add an English title or link a MAL/AniList id for accurate loading."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                            color = muted,
                        )
                    }
                }

                is DetailsUiState.Success -> {
                    val descText = details?.description?.takeIf { it.isNotBlank() && it != "null" }
                    if (descText != null) {
                        SectionHeader(text = if (ru) "Описание" else "Description", color = onBg)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = descText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = SnProFamily,
                                lineHeight = 22.sp,
                                lineBreak = LineBreak.Paragraph,
                                hyphens = Hyphens.Auto,
                            ),
                            color = muted,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.animateContentSize(animationSpec = MotionTokens.standard()),
                        )
                        Spacer(Modifier.height(14.dp))

                        val pillBg = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (descriptionExpanded) 180f else 0f,
                            animationSpec = MotionTokens.standard(),
                            label = "descExpandChevron",
                        )
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(pillBg)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { descriptionExpanded = !descriptionExpanded }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (descriptionExpanded) {
                                    if (ru) "Свернуть" else "Show less"
                                } else {
                                    if (ru) "Подробнее" else "Show more"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = SnProFamily,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = onBg,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = onBg,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = chevronRotation },
                            )
                        }
                    }
                }
            }

                // Запас под мини-док.
                Spacer(Modifier.height(140.dp))
            }
        }
    }
}

/** Заголовок секции с оранжевой засечкой слева — секции читаются как разделы, а не как подписи. */
@Composable
internal fun SectionHeader(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = color,
        )
    }
}

/**
 * Кнопка «в избранное» — круг того же роста, что и пилюли действий. Тумблер уже есть сквозь весь
 * стек ([com.example.myapplication.data.repository.AnimeRepository.toggleFavorite]), поэтому здесь
 * только вид: заполненная закладка на оранжевом при включённом, контурная — при выключенном.
 */
@Composable
private fun FavoriteButton(
    isFavorite: Boolean,
    isDark: Boolean,
    ru: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = when {
            isFavorite -> accent
            isDark -> Color.White.copy(alpha = 0.08f)
            else -> Color.Black.copy(alpha = 0.06f)
        },
        animationSpec = MotionTokens.standard(),
        label = "favoriteBg",
    )
    val tint by animateColorAsState(
        targetValue = if (isFavorite) Color.White else accent,
        animationSpec = MotionTokens.standard(),
        label = "favoriteTint",
    )
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            contentDescription = when {
                isFavorite && ru -> "Убрать из избранного"
                isFavorite -> "Remove from favorites"
                ru -> "В избранное"
                else -> "Add to favorites"
            },
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Чип сезона: «▶ S2 · 12 эп.». Залит брендовым оранжевым с иконкой play — чип ведёт на список
 * серий, и плоская серая пилюля читалась бы как тег, а не как кнопка перехода.
 * У выходящего сезона фон ярче ([BrandOrangeBright]) — статус виден без отдельного индикатора.
 */
@Composable
private fun SeasonChip(
    season: com.example.myapplication.domain.seasons.SeasonInfo,
    ru: Boolean,
    onClick: () -> Unit,
) {
    val fill = if (season.ongoing) BrandOrangeBright else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayCircleFilled,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (ru) {
                "${season.shortLabel(true)} · ${season.episodes} эп."
            } else {
                "${season.shortLabel(false)} · ${season.episodes} ep."
            },
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
        )
    }
}

/**
 * Poster-derived accent for the details sheet. Kept local to this screen so the Palette feature can
 * be removed without touching the image repository or the shared theme.
 */
@Composable
private fun rememberPosterAccent(imgPath: String?): Color {
    val fallback = MaterialTheme.colorScheme.primary
    var accent by remember(imgPath) { mutableStateOf(fallback) }

    LaunchedEffect(imgPath, fallback) {
        val path = imgPath ?: run {
            accent = fallback
            return@LaunchedEffect
        }
        accent = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) {
                    sample *= 2
                }
                val bitmap = BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample.coerceAtLeast(1)
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    },
                ) ?: return@runCatching fallback
                try {
                    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                    val rgb = palette.vibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                    rgb?.let(::Color) ?: fallback
                } finally {
                    bitmap.recycle()
                }
            }.getOrDefault(fallback)
        }
    }
    return accent
}
@Composable
private fun MetaChip(text: String, chipBg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(chipBg)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
        )
    }
}

/** Крупная пилюля-действие («Смотреть» / «Скачать») — референс-стиль, высота 52dp. */
@Composable
internal fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    fg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            ),
            color = fg,
        )
    }
}

// ==========================================
// Стеклянная кнопка «назад»
// ==========================================

@Composable
internal fun DetailsGlassBackButton(
    backdrop: Backdrop,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barTint = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.14f)
    val barBorder = Color.White.copy(alpha = if (isDark) 0.22f else 0.28f)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .glassControl(backdrop, CircleShape) {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(24f.dp.toPx())
                        lens(8f.dp.toPx(), 48f.dp.toPx())
                    },
                    onDrawSurface = { drawRect(barTint) },
                ).border(0.5.dp, barBorder, CircleShape)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            // Белая стрелка — для прозрачного «жидкого» стекла поверх арта. Матовая подложка
            // (glassControl в новом интерфейсе) в светлой теме почти белая: стрелка на ней терялась.
            tint = if (LocalModernUi.current) MaterialTheme.colorScheme.onSurface else Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ==========================================
// Мини-док: 2 вкладки, активная раскрывается с подписью
// ==========================================

@Composable
private fun DetailsMiniDock(
    backdrop: Backdrop,
    isDark: Boolean,
    language: AppLanguage,
    isManga: Boolean,
    activePage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glassEffects = rememberAdaptiveGlassEffects(GlassPreset.CompactNav)
    val dockShape = CircleShape
    val borderStroke = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)
    val ru = language == AppLanguage.RU
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(dockShape)
            .adaptiveGlassBackdrop(backdrop = backdrop, shape = dockShape, effects = glassEffects)
            .border(0.5.dp, borderStroke, dockShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MiniDockItem(
            icon = Icons.Rounded.Info,
            label = if (ru) "Детали" else "Details",
            selected = activePage == 0,
            isDark = isDark,
            onClick = { onSelect(0) },
        )
        MiniDockItem(
            icon = if (isManga) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.PlayArrow,
            label = when {
                isManga && ru -> "Главы"
                isManga -> "Chapters"
                ru -> "Серии"
                else -> "Episodes"
            },
            selected = activePage == 1,
            isDark = isDark,
            onClick = { onSelect(1) },
        )
    }
}

/**
 * Вкладка мини-дока. Активная — светлая пилюля + подпись (подпись выезжает по ширине,
 * expandHorizontally); неактивная — только приглушённая иконка. Всё на пружинах MotionTokens.
 */
@Composable
internal fun MiniDockItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val pillBg by animateColorAsState(
        targetValue = if (selected) {
            if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)
        } else {
            Color.Transparent
        },
        animationSpec = MotionTokens.standard(),
        label = "miniDockPillBg",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) contentColor else contentColor.copy(alpha = 0.55f),
        animationSpec = MotionTokens.standard(),
        label = "miniDockTint",
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(pillBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(animationSpec = MotionTokens.standard()) +
                fadeIn(animationSpec = androidx.compose.animation.core.tween(180)),
            exit = shrinkHorizontally(animationSpec = MotionTokens.sheetDismissForced()) +
                fadeOut(animationSpec = androidx.compose.animation.core.tween(120)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(7.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}
