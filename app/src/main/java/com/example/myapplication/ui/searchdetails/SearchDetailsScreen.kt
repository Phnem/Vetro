package com.example.myapplication.ui.searchdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.domain.search.apiRatingTo10
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.details.DetailsGlassBackButton
import com.example.myapplication.ui.details.DetailsScreen
import com.example.myapplication.ui.details.SectionHeader
import com.example.myapplication.ui.shared.StatusBarIconsOverArt
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.mininotif.MiniNotif
import com.example.myapplication.ui.shared.mininotif.MiniNotifKind
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * «Детали из поиска»: страница тайтла, которого ещё нет в
 * коллекции. На ней одно действие — «Добавить»: плюс уезжает в центр пилюли, крутится и становится
 * галочкой, затем идёт NameDrop-переход ([nameDropEffect]); на его пике содержимое подменяется
 * настоящими «Деталями» записанного тайтла — со «Смотреть», «Скачать», уведомлениями и всем
 * остальным, как у любого тайтла коллекции. В конце — плашка «добавлен в вашу коллекцию».
 */
@Composable
fun SearchDetailsScreen(
    navController: NavController,
    key: String,
    modifier: Modifier = Modifier,
    viewModel: SearchDetailsViewModel = koinViewModel(key = "search_details_$key") { parametersOf(key) },
) {
    val result = viewModel.result
    if (result == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    val language by viewModel.language.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val ru = language == AppLanguage.RU
    val isDark = isAppInDarkTheme()
    val view = LocalView.current
    val appContext = LocalContext.current.applicationContext
    val nameDrop = remember { NameDropState() }

    val title = when (language) {
        AppLanguage.EN -> result.titleEn?.takeIf { it.isNotBlank() } ?: result.title
        AppLanguage.RU -> result.titleRu?.takeIf { it.isNotBlank() } ?: result.title
    }

    // Уже лежащий в коллекции тайтл сразу показывается настоящими «Деталями» — без анимации.
    var showReal by remember { mutableStateOf(viewModel.startedInCollection) }
    val animeId = (addState as? SearchDetailsViewModel.AddState.Done)?.animeId

    LaunchedEffect(addState) {
        when (val s = addState) {
            is SearchDetailsViewModel.AddState.Done -> {
                if (viewModel.startedInCollection) return@LaunchedEffect
                // Галочке нужно мгновение, чтобы её увидели, прежде чем экран «потянет» к верху.
                delay(650)
                nameDrop.play(onPeak = { showReal = true })
                MiniNotif.show(
                    appContext,
                    if (ru) "«$title» добавлен в вашу коллекцию" else "“$title” added to your collection",
                )
            }
            is SearchDetailsViewModel.AddState.Failed -> {
                MiniNotif.show(
                    appContext,
                    s.message ?: if (ru) "Не удалось добавить тайтл" else "Couldn't add the title",
                    kind = MiniNotifKind.Error,
                )
            }
            else -> Unit
        }
    }

    Box(modifier.fillMaxSize().nameDropEffect(nameDrop)) {
        if (showReal && animeId != null) {
            DetailsScreen(navController = navController, animeId = animeId)
        } else {
            PreviewContent(
                result = result,
                title = title,
                ru = ru,
                isDark = isDark,
                pillPhase = when (addState) {
                    is SearchDetailsViewModel.AddState.Adding -> AddPillPhase.Working
                    is SearchDetailsViewModel.AddState.Done -> AddPillPhase.Done
                    else -> AddPillPhase.Idle
                },
                dissolve = { nameDrop.dissolve },
                onAdd = {
                    performHaptic(view, Haptic.Light)
                    viewModel.add()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewContent(
    result: ApiSearchResult,
    title: String,
    ru: Boolean,
    isDark: Boolean,
    pillPhase: AddPillPhase,
    dissolve: () -> Float,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenBg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val chipBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val backdrop = rememberLayerBackdrop {
        drawRect(screenBg)
        drawContent()
    }

    // Та же геометрия, что у настоящих «Деталей»: арт сверху, лист со скруглённым верхом наезжает на него.
    val heroHeight = 400.dp
    val sheetOverlap = 28.dp
    val sheetCorner = 30.dp
    val scrollState = rememberScrollState()
    val statusBarPx = WindowInsets.statusBars.getTop(density)
    val sheetTopPx = with(density) { (heroHeight - sheetOverlap).toPx() }
    val heroUnderStatusBar by remember(statusBarPx, sheetTopPx) {
        derivedStateOf { sheetTopPx - scrollState.value > statusBarPx }
    }
    StatusBarIconsOverArt(heroUnderStatusBar)

    val facts = remember(result, ru) { previewFacts(result, ru) }
    var descriptionExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(screenBg)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewportHeight = maxHeight

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(heroHeight + sheetCorner)
                        .graphicsLayer { translationY = -scrollState.value * 0.35f },
                ) {
                    if (result.posterUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(result.posterUrl).crossfade(true).build(),
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(if (isDark) Color(0xFF161616) else Color(0xFFE4E4E8)),
                        )
                    }
                    Box(
                        Modifier
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

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState, flingBehavior = IosScroll.flingBehavior()),
                ) {
                    Spacer(Modifier.height(heroHeight - sheetOverlap))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewportHeight)
                            .clip(RoundedCornerShape(topStart = sheetCorner, topEnd = sheetCorner))
                            .background(screenBg)
                            .padding(horizontal = 20.dp),
                    ) {
                        GrabberHandle(isDark)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = SnProFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                                lineHeight = 34.sp,
                            ),
                            color = onBg,
                        )

                        Spacer(Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PreviewChip(typeLabel(result, ru), chipBg, muted)
                            facts.headline.forEach { PreviewChip(it, chipBg, muted) }
                        }

                        Spacer(Modifier.height(18.dp))
                        AddToCollectionPill(
                            phase = pillPhase,
                            label = if (ru) "Добавить" else "Add",
                            onClick = onAdd,
                            dissolve = dissolve,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (ru) {
                                "Добавьте тайтл в коллекцию — и станут доступны просмотр, загрузка и уведомления."
                            } else {
                                "Add the title to your collection to unlock watching, downloads and notifications."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                            color = muted,
                        )

                        if (facts.cards.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(text = if (ru) "Информация" else "Information", color = onBg)
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                maxItemsInEachRow = 2,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                facts.cards.forEach { (label, value) ->
                                    PreviewFactCard(label, value, isDark, Modifier.weight(1f))
                                }
                            }
                        }

                        if (result.genres.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(text = if (ru) "Жанры" else "Genres", color = onBg)
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                result.genres.forEach { PreviewChip(it, chipBg, muted) }
                            }
                        }

                        if (result.description.isNotBlank()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(text = if (ru) "Описание" else "Synopsis", color = onBg)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = result.description.trim(),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = SnProFamily,
                                    lineHeight = 22.sp,
                                ),
                                color = muted,
                                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { descriptionExpanded = !descriptionExpanded },
                            )
                        }
                        Spacer(Modifier.height(48.dp))
                    }
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
    }
}

@Composable
private fun PreviewChip(text: String, chipBg: Color, fg: Color) {
    Box(
        Modifier
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

@Composable
private fun PreviewFactCard(label: String, value: String, isDark: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private class PreviewFacts(val headline: List<String>, val cards: List<Pair<String, String>>)

private fun typeLabel(result: ApiSearchResult, ru: Boolean): String =
    when (MediaType.fromCategoryType(result.categoryType)) {
        MediaType.ANIME -> if (ru) "Аниме" else "Anime"
        MediaType.MANGA -> if (ru) "Манга" else "Manga"
        MediaType.MOVIE -> if (ru) "Фильм" else "Movie"
        MediaType.SERIES -> if (ru) "Сериал" else "Series"
        else -> result.type.ifBlank { result.categoryType }
    }

/** Всё, что результат поиска сообщил сам: без дополнительных запросов, поэтому страница открывается сразу. */
private fun previewFacts(result: ApiSearchResult, ru: Boolean): PreviewFacts {
    val rating10 = apiRatingTo10(result.rating)
    val episodes = (result.totalEpisodes ?: result.episodes).takeIf { it > 0 }
    val status = when (result.isOngoing) {
        true -> if (ru) "Онгоинг" else "Ongoing"
        false -> if (ru) "Вышел" else "Released"
        null -> null
    }
    val headline = buildList {
        if (rating10 > 0f) add("★ ${RatingScale.format(rating10)}")
        result.seasonYear?.let { add(it.toString()) }
        if (episodes != null && result.categoryType != "MOVIE") add(if (ru) "$episodes эп." else "$episodes ep.")
        status?.let { add(it) }
    }
    val cards = buildList {
        status?.let { add((if (ru) "Статус" else "Status") to it) }
        episodes?.let { add((if (ru) "Эпизоды" else "Episodes") to it.toString()) }
        result.format?.takeIf { it.isNotBlank() }?.let { add((if (ru) "Формат" else "Format") to it.uppercase()) }
        result.studio?.takeIf { it.isNotBlank() }?.let { add((if (ru) "Студия" else "Studio") to it) }
        result.seasonYear?.let { add((if (ru) "Год" else "Year") to it.toString()) }
        result.source.takeIf { it.isNotBlank() }?.let { add((if (ru) "Источник" else "Source") to it) }
    }
    return PreviewFacts(headline, cards)
}
