package com.example.myapplication.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.enrichment.PosterCandidate
import com.example.myapplication.domain.enrichment.TitleCandidate
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.NoOverscroll
import com.example.myapplication.ui.shared.components.accent
import com.example.myapplication.ui.shared.components.icon
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.sheetFloatingMargin
import com.example.myapplication.ui.shared.theme.sheetPanelShape
import com.example.myapplication.ui.shared.theme.iosSheetContainer

/** Что сейчас выбирает пользователь в шторке меню «⋯» экрана Details. */
sealed interface DetailsPickerState {
    data object None : DetailsPickerState

    data class Covers(
        val items: List<PosterCandidate> = emptyList(),
        val loading: Boolean = false,
        val saving: Boolean = false,
    ) : DetailsPickerState

    data class Sources(
        val query: String = "",
        val items: List<TitleCandidate> = emptyList(),
        val loading: Boolean = false,
        val saving: Boolean = false,
    ) : DetailsPickerState

    /** [chosen] — выбор пользователя (null = «авто»), [effective] — то, что показывается сейчас. */
    data class Status(
        val chosen: WatchStatus?,
        val effective: WatchStatus,
    ) : DetailsPickerState
}

/**
 * Шторка выбора: обложка из других каталогов или вариант тайтла для «Источник неверен».
 * Скрим, подъём и свайп вниз — как у шторки рекомендаций.
 */
@Composable
internal fun DetailsPickerSheet(
    state: DetailsPickerState,
    ru: Boolean,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onPickCover: (PosterCandidate) -> Unit,
    onPickSource: (TitleCandidate) -> Unit,
    onNoneOfThese: () -> Unit,
    onSearch: (String) -> Unit,
    onPickStatus: (WatchStatus?) -> Unit,
) {
    if (state == DetailsPickerState.None) return
    com.example.myapplication.ui.shared.SheetGateEffect(true)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(visible) {
        if (!visible) {
            kotlinx.coroutines.delay(250)
            onDismiss()
        }
    }
    BackHandler { visible = false }
    val swipe = rememberIosSheetSwipe { visible = false }

    Box(Modifier.fillMaxSize().zIndex(10f), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = OverlayThemeTokens.scrimAlpha(isDark) * com.example.myapplication.ui.shared.theme.SheetStyle.scrimFactor))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        visible = false
                    },
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = MotionTokens.sheetPresent()) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = MotionTokens.sheetDismissForced()) + fadeOut(),
        ) {
            BoxWithConstraints {
                Column(
                    modifier = Modifier
                        .sheetFloatingMargin()
                        .then(swipe.panelModifier)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * 0.9f)
                        .iosSheetContainer(
                            sheetPanelShape(),
                            isDark,
                            IosDesign.sheetSurface(isDark),
                        )
                        .imePadding()
                        .navigationBarsPadding(),
                ) {
                    GrabberHandle(isDark, modifier = swipe.handleModifier)
                    NoOverscroll {
                        when (state) {
                            is DetailsPickerState.Covers -> CoversContent(state, ru, onPickCover)
                            is DetailsPickerState.Sources -> SourcesContent(state, ru, onPickSource, onNoneOfThese, onSearch)
                            is DetailsPickerState.Status -> StatusContent(state, ru, isDark, onPickStatus)
                            DetailsPickerState.None -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            title,
            fontFamily = SnProFamily,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            subtitle,
            fontFamily = SnProFamily,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SavingVeil() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(color = BrandOrangeBright, strokeWidth = 2.5.dp) }
}

// ——— Обложки ———

@Composable
private fun CoversGrid(state: DetailsPickerState.Covers, onPick: (PosterCandidate) -> Unit) {
    Box {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.items, key = { it.url }) { poster ->
                Column(
                    Modifier.clickable(enabled = !state.saving) { onPick(poster) },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(poster.url).crossfade(true).build(),
                        contentDescription = poster.caption,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Text(
                        poster.source,
                        fontFamily = SnProFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandOrangeBright,
                    )
                    Text(
                        poster.caption.orEmpty(),
                        fontFamily = SnProFamily,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.saving) SavingVeil()
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CoversContent(
    state: DetailsPickerState.Covers,
    ru: Boolean,
    onPick: (PosterCandidate) -> Unit,
) {
    SheetTitle(
        title = if (ru) "Сменить обложку" else "Change cover",
        subtitle = if (ru) {
            "Обложки этого тайтла из других каталогов. Под каждой — как тайтл назван там."
        } else {
            "Covers for this title from other catalogues, captioned with the name used there."
        },
    )
    when {
        state.loading -> Centered { CircularProgressIndicator(color = BrandOrangeBright, strokeWidth = 2.5.dp) }
        state.items.isEmpty() -> Centered {
            Text(
                if (ru) "Обложки не найдены" else "No covers found",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SnProFamily,
            )
        }
        else -> Box(Modifier.weight(1f, fill = false)) { CoversGrid(state, onPick) }
    }
}

// ——— Статус просмотра ———

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.StatusContent(
    state: DetailsPickerState.Status,
    ru: Boolean,
    isDark: Boolean,
    onPick: (WatchStatus?) -> Unit,
) {
    SheetTitle(
        title = if (ru) "Статус просмотра" else "Watch status",
        subtitle = if (ru) {
            "Виден в Details и работает в фильтре сортировки. Без выбора статус определяется по прогрессу."
        } else {
            "Shown in Details and used by the sort filter. Without a choice it follows your progress."
        },
    )
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        modifier = Modifier.weight(1f, fill = false),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(WatchStatus.entries, key = { it.name }) { status ->
            StatusRow(
                status = status,
                ru = ru,
                isDark = isDark,
                selected = state.effective == status,
                accent = status.accent(neutral),
                onClick = { onPick(status) },
            )
        }
        if (state.chosen != null) {
            item(key = "reset") {
                Text(
                    text = if (ru) "Сбросить к автоматическому" else "Reset to automatic",
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = BrandOrangeBright,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPick(null) }
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    status: WatchStatus,
    ru: Boolean,
    isDark: Boolean,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val bg = if (selected) accent.copy(alpha = if (isDark) 0.20f else 0.13f) else IosDesign.elevatedCard(isDark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isDark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = status.icon(),
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = status.label(ru),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = status.hint(ru),
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(accent)
                    else Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ——— Источник ———

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SourcesContent(
    state: DetailsPickerState.Sources,
    ru: Boolean,
    onPick: (TitleCandidate) -> Unit,
    onNone: () -> Unit,
    onSearch: (String) -> Unit,
) {
    SheetTitle(
        title = if (ru) "Какой это тайтл?" else "Which title is this?",
        subtitle = if (ru) {
            "Выберите верное название: оно запишется в карточку, и по нему подключатся каталоги. " +
                "Можно искать своим запросом или по id."
        } else {
            "Pick the right title: it is saved to the card and catalogues connect through it. " +
                "You can search with your own query or an id."
        },
    )
    var query by remember { mutableStateOf(state.query) }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text(if (ru) "Название или id" else "Title or id") },
        trailingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(22.dp).clickable { onSearch(query) },
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )

    Box(Modifier.weight(1f, fill = false)) {
        if (state.loading) {
            Centered { CircularProgressIndicator(color = BrandOrangeBright, strokeWidth = 2.5.dp) }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.key }) { candidate ->
                    CandidateRow(candidate, enabled = !state.saving, onClick = { onPick(candidate) })
                }
                if (state.items.isEmpty()) {
                    item {
                        Text(
                            if (ru) {
                                "Ничего не нашлось — уточните запрос или выберите «Ни один из них»."
                            } else {
                                "Nothing found — refine the query or choose \"None of these\"."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SnProFamily,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !state.saving, onClick = onNone)
                            .padding(horizontal = 14.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = BrandOrangeBright, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (ru) "Ни один из них" else "None of these",
                                color = BrandOrangeBright,
                                fontFamily = SnProFamily,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (ru) "Снять привязку и пометить тайтл как ошибку" else "Clear the link and flag the title",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = SnProFamily,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
        if (state.saving) SavingVeil()
    }
}

@Composable
private fun CandidateRow(candidate: TitleCandidate, enabled: Boolean, onClick: () -> Unit) {
    val meta = listOfNotNull(
        candidate.format?.uppercase(),
        candidate.episodes.takeIf { it > 0 }?.let { "$it ep." },
        candidate.year?.toString(),
        candidate.sources.joinToString(", ").takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(candidate.posterUrl).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 46.dp, height = 66.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                candidate.titleEn,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            candidate.titleRu?.let {
                Text(
                    it,
                    fontFamily = SnProFamily,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    fontFamily = SnProFamily,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}
