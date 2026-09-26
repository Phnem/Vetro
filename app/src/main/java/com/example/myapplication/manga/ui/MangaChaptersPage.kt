package com.example.myapplication.manga.ui

import com.example.myapplication.ui.shared.theme.IosScroll
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.manga.data.DownloadProgress
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.source.MangaSourceResults
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** Каталог расширений Mihon — временная замена внутреннему экрану, пока нет `mihon-compat`. */
private const val MIHON_EXTENSIONS_URL = "https://github.com/keiyoushi/extensions"

/**
 * Запас снизу под плавающий мини-док Details (56dp + 16dp отступ + навигационная полоса).
 *
 * Общая константа, а не число по месту: список глав её учитывал, а выбор источника — нет, и
 * последняя строка выбора («Расширения Mihon») уезжала ровно под док, где её нельзя ни прочесть,
 * ни нажать.
 */
private val DockClearance = 120.dp

/**
 * Вкладка «Главы» в Details для тайтлов с [com.example.myapplication.data.models.MediaType.MANGA].
 *
 * Два состояния по существу: тайтл ещё не сопоставлен с мангой у источника (выбор из результатов
 * поиска) или уже сопоставлен (список глав). Третьего не бывает — именно поэтому привязка живёт
 * отдельным подтверждённым фактом, а не эвристикой при каждом открытии.
 */
@Composable
fun MangaChaptersPage(
    animeId: String,
    animeTitle: String,
    animeTitleEn: String?,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    // Ключ по тайтлу: без него разные тайтлы в одном ViewModelStore делят одну модель и оглавление.
    viewModel: MangaLibraryViewModel = koinViewModel(key = "manga_chapters_$animeId") {
        parametersOf(animeId, animeTitle, animeTitleEn.orEmpty())
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ru = language == AppLanguage.RU
    val context = LocalContext.current
    val isDark = isAppInDarkTheme()

    // Фон — во всю высоту, включая зону статус-бара; отступ под плавающую шапку получает только
    // содержимое. Пока отступ накладывался снаружи, он ужимал и фон, и над градиентом оставалась
    // чёрная полоса корневого экрана Details.
    // Порядок модификаторов существенен: background стоит ДО отступов, поэтому фон занимает всю
    // высоту, а под плавающую шапку отодвигается только содержимое.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IosDesign.screenGradient(isDark))
            .statusBarsPadding()
            .padding(top = 56.dp),
    ) {
        when (val current = state) {
            MangaLibraryUiState.Loading -> CircularProgressIndicator(
                color = BrandOrange,
                modifier = Modifier.align(Alignment.Center),
            )

            is MangaLibraryUiState.SourcePicker -> SourcePickerContent(
                state = current,
                ru = ru,
                onSearch = viewModel::search,
                onPick = viewModel::bind,
            )

            is MangaLibraryUiState.Chapters -> ChaptersContent(
                state = current,
                ru = ru,
                onOpen = { chapter ->
                    MangaReaderActivity.start(
                        context = context,
                        animeId = animeId,
                        chapter = chapter,
                        // Платные главы в ридер не отдаём: «следующая» не должна упираться в замок.
                        // Порядок здесь канонический (по возрастанию), а не тот, в котором список
                        // показан: «следующая глава» в ридере не зависит от сортировки на экране.
                        chapters = current.chapters.filterNot { it.paid },
                    )
                },
                onToggleRead = viewModel::setRead,
                onToggleDownload = viewModel::toggleDownload,
                onLanguage = viewModel::selectLanguage,
                onRefresh = viewModel::refresh,
                onChangeSource = viewModel::unbind,
            )

            is MangaLibraryUiState.Error -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (ru) {
                        "У источника нет глав для этого тайтла"
                    } else {
                        "The source has no chapters for this title"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (ru) "Выбрать другой источник" else "Pick another source",
                    color = BrandOrange,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { viewModel.unbind() },
                )
                Spacer(Modifier.height(16.dp))
                MihonExtensionsButton(ru = ru)
            }
        }
    }
}

@Composable
private fun SourcePickerContent(
    state: MangaLibraryUiState.SourcePicker,
    ru: Boolean,
    onSearch: (String) -> Unit,
    onPick: (MangaItem) -> Unit,
) {
    var query by remember(state.query) { mutableStateOf(state.query) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = {
                Text(if (ru) "Название у источника" else "Title at the source")
            },
            trailingIcon = {
                IconButton(onClick = { onSearch(query) }) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = BrandOrange)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            text = if (ru) {
                "Выберите мангу, которая соответствует тайтлу — привязка сохранится."
            } else {
                "Pick the manga matching this title — the binding is saved."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (state.searching) {
            CircularProgressIndicator(
                color = BrandOrange,
                modifier = Modifier
                    .padding(24.dp)
                    .align(Alignment.CenterHorizontally),
            )
            return@Column
        }

        if (state.results.isEmpty()) {
            Text(
                text = if (ru) "Ничего не найдено" else "Nothing found",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            flingBehavior = IosScroll.flingBehavior(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = DockClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.results.forEach { group: MangaSourceResults ->
                item(key = "header_${group.sourceId.value}") {
                    Text(
                        text = group.sourceName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(group.items, key = { "${group.sourceId.value}_${it.key}" }) { item ->
                    SearchResultRow(item = item, onClick = { onPick(item) })
                }
            }
            item(key = "mihon_extensions") {
                Box(modifier = Modifier.padding(top = 16.dp)) {
                    MihonExtensionsButton(ru = ru)
                }
            }
        }
    }
}

/**
 * Ссылка на каталог расширений Mihon. Пока внутреннего экрана расширений нет, отдаём страницу
 * внешнему браузеру — иначе тупик: нужного источника нет, и добавить его неоткуда.
 */
@Composable
private fun MihonExtensionsButton(ru: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, MIHON_EXTENSIONS_URL.toUri()))
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Extension,
            contentDescription = null,
            tint = BrandOrange,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (ru) "Расширения Mihon" else "Mihon extensions",
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = SnProFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SearchResultRow(item: MangaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SquircleShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 44.dp, height = 62.dp)
                .clip(SquircleShape(10.dp))
                .background(Color.Black.copy(alpha = 0.2f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                item.altTitle?.takeIf { it != item.title },
                item.year?.toString(),
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ==========================================
// Список глав
// ==========================================

/** Фильтр списка. «Загруженные» — то, что лежит файлами, а не то, что качается прямо сейчас. */
private enum class ChapterFilter { All, Unread, Downloaded }

/** Один том (или весь список, если томов у источника нет) вместе с его главами. */
private data class ChapterGroup(
    val key: String,
    val label: String?,
    val chapters: List<MangaChapter>,
)

@Composable
private fun ChaptersContent(
    state: MangaLibraryUiState.Chapters,
    ru: Boolean,
    onOpen: (MangaChapter) -> Unit,
    onToggleRead: (MangaChapter, Boolean) -> Unit,
    onToggleDownload: (MangaChapter) -> Unit,
    onLanguage: (String?) -> Unit,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    var filter by rememberSaveable { mutableStateOf(ChapterFilter.All) }
    // По умолчанию сверху свежие главы: у долгих тайтлов иначе первое, что видит читатель, —
    // пролог десятилетней давности.
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    // Хранится «что развёрнуто», а не «что свёрнуто»: у тайтла на десятки томов развёрнутый
    // по умолчанию список — это сотни строк, через которые надо пролистать до нужного тома.
    // При обратном хранении дефолт пришлось бы набивать ключами всех групп, и он бы разъезжался
    // при каждой смене фильтра.
    var expanded by remember(state.binding.mangaKey) { mutableStateOf(emptySet<String>()) }

    fun isRead(chapter: MangaChapter) = state.progress[chapter.key]?.read == true
    fun isDownloaded(chapter: MangaChapter) = chapter.key in state.downloadedKeys

    val unreadCount = state.chapters.count { !isRead(it) }
    val downloadedCount = state.chapters.count { isDownloaded(it) }

    val visible = state.chapters.filter { chapter ->
        when (filter) {
            ChapterFilter.All -> true
            ChapterFilter.Unread -> !isRead(chapter)
            ChapterFilter.Downloaded -> isDownloaded(chapter)
        }
    }
    val ordered = if (newestFirst) visible.asReversed() else visible
    val groups = remember(ordered, ru) { groupByVolume(ordered, ru) }

    // Фон (тот же, что у настроек и меню серий) поднят в MangaChaptersPage, чтобы доходить до
    // верхнего края экрана. Карточки ниже полупрозрачны — засветка читается сквозь них.
    Column(modifier = Modifier.fillMaxSize()) {
        ChaptersHeader(
            state = state,
            ru = ru,
            isDark = isDark,
            onRefresh = onRefresh,
            onChangeSource = onChangeSource,
        )

        // ——— Фильтры + направление сортировки ———
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(
                    label = if (ru) "Все главы" else "All chapters",
                    count = state.chapters.size,
                    selected = filter == ChapterFilter.All,
                    isDark = isDark,
                    onClick = { filter = ChapterFilter.All },
                )
                FilterPill(
                    label = if (ru) "Непрочитанные" else "Unread",
                    count = unreadCount,
                    selected = filter == ChapterFilter.Unread,
                    isDark = isDark,
                    onClick = { filter = ChapterFilter.Unread },
                )
                FilterPill(
                    label = if (ru) "Загруженные" else "Downloaded",
                    count = downloadedCount,
                    selected = filter == ChapterFilter.Downloaded,
                    isDark = isDark,
                    onClick = { filter = ChapterFilter.Downloaded },
                )
            }
            SortButton(
                newestFirst = newestFirst,
                isDark = isDark,
                ru = ru,
                onClick = { newestFirst = !newestFirst },
                modifier = Modifier.padding(end = 16.dp),
            )
        }

        // ——— Языки источника ———
        if (state.availableLanguages.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LanguageChip(
                    label = if (ru) "Все" else "All",
                    selected = state.binding.preferredLanguage == null,
                    onClick = { onLanguage(null) },
                )
                state.availableLanguages.forEach { lang ->
                    LanguageChip(
                        label = lang.uppercase(),
                        selected = state.binding.preferredLanguage == lang,
                        onClick = { onLanguage(lang) },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            Text(
                text = when (filter) {
                    ChapterFilter.Unread -> if (ru) "Всё прочитано" else "Everything is read"
                    ChapterFilter.Downloaded -> if (ru) "Нет загруженных глав" else "No downloaded chapters"
                    ChapterFilter.All -> if (ru) "Глав нет" else "No chapters"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SnProFamily,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }

        LazyColumn(
            flingBehavior = IosScroll.flingBehavior(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = DockClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { group ->
                // Группа без заголовка — это тайтл, у которого источник не проставил тома
                // (groupByVolume отдаёт одну группу с label = null). Свернуть её нечем: заголовка
                // с переключателем у неё нет. Поэтому она всегда развёрнута, иначе свёрнутый
                // по умолчанию список спрятал бы вообще все главы без способа его открыть.
                val isExpanded = group.label == null || group.key in expanded
                if (group.label == null) {
                    // Группа без тома свернуться не может, зато глав в ней бывают сотни — она
                    // обязана остаться ленивой, иначе список измерял бы их все разом.
                    itemsIndexed(
                        items = group.chapters,
                        key = { _, chapter -> "${group.key}_${chapter.key}" },
                    ) { index, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            shape = groupRowShape(index, group.chapters.size, false),
                            progressFraction = state.progress[chapter.key]?.fraction ?: 0f,
                            read = isRead(chapter),
                            downloaded = isDownloaded(chapter),
                            downloading = state.downloading[chapter.key],
                            isDark = isDark,
                            ru = ru,
                            onClick = { onOpen(chapter) },
                            onToggleRead = { onToggleRead(chapter, it) },
                            onToggleDownload = { onToggleDownload(chapter) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = MotionTokens.seasonExpansion(),
                                placementSpec = MotionTokens.seasonExpansion(),
                                fadeOutSpec = MotionTokens.seasonExpansion(),
                            ),
                        )
                    }
                    return@forEach
                }
                // Том целиком — ОДИН элемент списка: шапка плюс тело под общей анимацией. Раньше
                // свёрнутый том просто выкидывал свои `itemsIndexed` из LazyColumn, и анимировать
                // было нечего — строки исчезали вместе с элементами.
                //
                // Держать шапку и тело раздельными элементами тоже нельзя: `spacedBy` ставит зазор
                // между элементами независимо от их высоты, и у свёрнутого тома между шапками
                // набегал бы двойной отступ.
                item(key = "vol_${group.key}") {
                    Column {
                        VolumeHeader(
                            label = group.label,
                            coverUrl = state.binding.coverUrl,
                            total = group.chapters.size,
                            read = group.chapters.count { isRead(it) },
                            collapsed = !isExpanded,
                            isDark = isDark,
                            ru = ru,
                            onToggle = {
                                expanded = if (isExpanded) expanded - group.key else expanded + group.key
                            },
                            onDownloadAll = {
                                group.chapters
                                    .filterNot { it.paid || isDownloaded(it) }
                                    .filter { state.downloading[it.key] == null }
                                    .forEach(onToggleDownload)
                            },
                        )
                        AnimatedVisibility(
                            visible = isExpanded,
                            // Пружина — seasonExpansion: тома здесь ровно то же, что сезоны в
                            // Details, и разъезжаться в моциях этим двум спискам незачем.
                            enter = expandVertically(MotionTokens.seasonExpansion()) +
                                fadeIn(MotionTokens.seasonExpansion()),
                            exit = shrinkVertically(MotionTokens.seasonExpansion()) +
                                fadeOut(MotionTokens.seasonExpansion()),
                        ) {
                            Column(
                                // Отступ от шапки живёт ВНУТРИ анимируемой области, иначе он
                                // остался бы висеть под шапкой свёрнутого тома.
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                group.chapters.forEachIndexed { index, chapter ->
                                    ChapterRow(
                                        chapter = chapter,
                                        shape = groupRowShape(index, group.chapters.size, true),
                                        progressFraction = state.progress[chapter.key]?.fraction ?: 0f,
                                        read = isRead(chapter),
                                        downloaded = isDownloaded(chapter),
                                        downloading = state.downloading[chapter.key],
                                        isDark = isDark,
                                        ru = ru,
                                        onClick = { onOpen(chapter) },
                                        onToggleRead = { onToggleRead(chapter, it) },
                                        onToggleDownload = { onToggleDownload(chapter) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Тома идут отдельными карточками; если источник томов не проставил — весь список одной
 * карточкой без заголовка. Главы без тома у источника с томами собираются в «Прочее»:
 * прятать их нельзя, а вклеивать в чужой том — врать.
 */
private fun groupByVolume(chapters: List<MangaChapter>, ru: Boolean): List<ChapterGroup> {
    if (chapters.none { it.volume != null }) {
        return listOf(ChapterGroup(key = "all", label = null, chapters = chapters))
    }
    return chapters
        .groupBy { it.volume }
        .map { (volume, items) ->
            val number = volume?.let { v ->
                if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            }
            ChapterGroup(
                key = number ?: "extra",
                label = when {
                    number != null && ru -> "Том $number"
                    number != null -> "Vol. $number"
                    ru -> "Прочее"
                    else -> "Other"
                },
                chapters = items,
            )
        }
}

/** Наружные углы карточек томов и глав. В масштабе остальных карточек приложения (§3.1 `RadiusMd`+). */
private val ChapterCardRadius = 22.dp

/** Внутренние углы соседних строк одной группы — намеренно мелкие, чтобы группа читалась цельной. */
private val ChapterCardInnerRadius = 6.dp

/**
 * Заливка строки главы. Затемнение, а не осветление: под карточками теперь тёплый градиент, и
 * белая плёнка поверх него давала бы мутную дымку вместо стекла. Тот же приём, что у карточек
 * серий (`ModernDetailsEpisodesPage`).
 */
@Composable
private fun chapterRowBackground(isDark: Boolean): Color =
    if (isDark) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.72f)

/** Шапка тома — на ступень плотнее строк: она держит группу и обязана читаться первой. */
@Composable
private fun volumeHeaderBackground(isDark: Boolean): Color =
    if (isDark) Color.Black.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.82f)

/**
 * Первая и последняя строки группы скругляются наружу — группа читается одной карточкой.
 *
 * Радиусы в масштабе остального приложения (карточки — 18–26dp): прежние 18/4 были мельче всего,
 * что рядом, и список глав выглядел чужим.
 */
private fun groupRowShape(index: Int, size: Int, insideVolume: Boolean): Shape {
    val big = ChapterCardRadius
    val small = ChapterCardInnerRadius
    // У тома верх карточки уже скруглён шапкой, поэтому первая строка под ней — почти прямая.
    val top = if (index == 0 && !insideVolume) big else small
    val bottom = if (index == size - 1) big else small
    return SquircleCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

@Composable
private fun ChaptersHeader(
    state: MangaLibraryUiState.Chapters,
    ru: Boolean,
    isDark: Boolean,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
) {
    val volumes = state.chapters.mapNotNull { it.volume }
    val volumeRange = if (volumes.isNotEmpty()) {
        val min = volumes.min()
        val max = volumes.max()
        val fmt = { v: Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString() }
        if (min == max) {
            if (ru) "Том ${fmt(min)}" else "Vol. ${fmt(min)}"
        } else {
            if (ru) "Том ${fmt(min)}–${fmt(max)}" else "Vol. ${fmt(min)}–${fmt(max)}"
        }
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.binding.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = SnProFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaPill(
                    text = if (ru) {
                        "${state.chapters.size} ${chapterPlural(state.chapters.size)}"
                    } else {
                        "${state.chapters.size} ch."
                    },
                    isDark = isDark,
                )
                volumeRange?.let { MetaPill(text = it, isDark = isDark) }
                MetaPill(text = state.binding.sourceId, isDark = isDark)
            }
        }
        if (state.refreshing) {
            CircularProgressIndicator(
                color = BrandOrange,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(12.dp)
                    .size(18.dp),
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = if (ru) "Обновить оглавление" else "Refresh chapters",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onChangeSource) {
            Icon(
                Icons.Rounded.SwapHoriz,
                contentDescription = if (ru) "Сменить источник" else "Change source",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetaPill(text: String, isDark: Boolean) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = SnProFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f))
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** Выбранный фильтр — контрастная пилюля (белая в тёмной теме), остальные приглушены. */
@Composable
private fun FilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        selected && isDark -> Color.White
        selected -> Color.Black
        isDark -> Color.White.copy(alpha = 0.07f)
        else -> Color.Black.copy(alpha = 0.05f)
    }
    val fg = when {
        selected && isDark -> Color.Black
        selected -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "$label ($count)",
        color = fg,
        fontFamily = SnProFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun SortButton(
    newestFirst: Boolean,
    isDark: Boolean,
    ru: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (newestFirst) 0f else 180f,
        animationSpec = MotionTokens.standard(),
        label = "chapterSortArrow",
    )
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.ArrowDownward,
            contentDescription = if (ru) "Порядок глав" else "Chapter order",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * Шапка тома: обложка тайтла, номер, прогресс чтения, «скачать весь том» и сворачивание.
 * Обложка здесь — тайтловая, а не томовая: своих обложек томов источники не отдают.
 */
@Composable
private fun VolumeHeader(
    label: String,
    coverUrl: String?,
    total: Int,
    read: Int,
    collapsed: Boolean,
    isDark: Boolean,
    ru: Boolean,
    onToggle: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                SquircleCornerShape(
                    ChapterCardRadius,
                    ChapterCardRadius,
                    ChapterCardInnerRadius,
                    ChapterCardInnerRadius,
                )
            )
            .background(volumeHeaderBackground(isDark))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 42.dp, height = 56.dp)
                .clip(SquircleShape(10.dp))
                .background(Color.Black.copy(alpha = 0.25f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = SnProFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (ru) {
                    "$total ${chapterPlural(total)} · Прочитано $read/$total"
                } else {
                    "$total ch. · Read $read/$total"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SnProFamily,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(BrandOrange.copy(alpha = 0.16f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    performHaptic(view, Haptic.Light)
                    onDownloadAll()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = if (ru) "Скачать том" else "Download volume",
                tint = BrandOrange,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (collapsed) 180f else 0f,
                animationSpec = MotionTokens.standard(),
                label = "volumeChevron",
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = if (collapsed) {
                    if (ru) "Развернуть том" else "Expand volume"
                } else {
                    if (ru) "Свернуть том" else "Collapse volume"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/**
 * Одна кнопка на три состояния: скачать → идёт загрузка (кольцо прогресса, тап отменяет) →
 * скачано (тап удаляет). Отдельных кнопок «отменить» и «удалить» нет намеренно — в строке главы
 * им негде поместиться, а действие всегда однозначно.
 */
@Composable
private fun DownloadButton(
    downloaded: Boolean,
    downloading: DownloadProgress?,
    ru: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        when {
            downloading != null -> CircularProgressIndicator(
                progress = { downloading.fraction },
                color = BrandOrange,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )

            downloaded -> Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = if (ru) "Удалить загрузку" else "Delete download",
                tint = BrandOrange,
                modifier = Modifier.size(20.dp),
            )

            else -> Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = if (ru) "Скачать главу" else "Download chapter",
                tint = BrandOrange.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: MangaChapter,
    shape: Shape,
    progressFraction: Float,
    read: Boolean,
    downloaded: Boolean,
    downloading: DownloadProgress?,
    isDark: Boolean,
    ru: Boolean,
    onClick: () -> Unit,
    onToggleRead: (Boolean) -> Unit,
    onToggleDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowBg = chapterRowBackground(isDark)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rowBg)
            .clickable(enabled = !chapter.paid, onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.listTitle(ru),
                color = if (read || chapter.paid) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                fontFamily = SnProFamily,
                fontSize = 14.sp,
                fontWeight = if (read) FontWeight.Medium else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Оранжевая точка = глава ещё не прочитана; у прочитанных её роль берёт галочка.
                if (!read && !chapter.paid) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(BrandOrange),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                val subtitle = listOfNotNull(
                    chapter.language?.uppercase(),
                    chapter.scanlator,
                    chapter.pageCount.takeIf { it > 0 }?.let { if (ru) "$it стр." else "$it p." },
                    formatChapterDate(chapter.publishedAt, ru),
                    if (downloaded) (if (ru) "офлайн" else "offline") else null,
                ).joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SnProFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Полосу тянем только для начатых-недочитанных: у прочитанных её роль берёт галочка.
            if (progressFraction > 0f && !read) {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { progressFraction },
                    color = BrandOrange,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        if (chapter.paid) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = if (ru) "Платная глава" else "Paid chapter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .padding(12.dp)
                    .size(18.dp),
            )
        } else {
            DownloadButton(
                downloaded = downloaded,
                downloading = downloading,
                ru = ru,
                onClick = onToggleDownload,
            )
            IconButton(onClick = { onToggleRead(!read) }) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = if (ru) "Отметить прочитанной" else "Mark as read",
                    tint = if (read) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = SnProFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) BrandOrange else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** «Глава 179 — Эпилог»: в списке место есть, в доке ридера — нет, поэтому формат свой. */
private fun MangaChapter.listTitle(ru: Boolean): String {
    val number = numberLabel?.let { if (ru) "Глава $it" else "Chapter $it" }
    val name = title?.takeIf { it.isNotBlank() }
    return listOfNotNull(number, name).joinToString(" — ")
        .ifBlank { if (ru) "Глава" else "Chapter" }
}

private fun chapterPlural(count: Int): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return "глав"
    return when (count % 10) {
        1 -> "глава"
        2, 3, 4 -> "главы"
        else -> "глав"
    }
}

private val RuDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
private val EnDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/** `publishedAt == 0` = источник даты не отдал; тогда в подписи её просто нет. */
private fun formatChapterDate(publishedAt: Long, ru: Boolean): String? {
    if (publishedAt <= 0L) return null
    return runCatching {
        Instant.ofEpochMilli(publishedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(if (ru) RuDateFormatter else EnDateFormatter)
    }.getOrNull()
}
