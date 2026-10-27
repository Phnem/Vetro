package com.example.myapplication.audiobooks.ui.details

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.audiobooks.data.BookPeopleOverrides
import com.example.myapplication.audiobooks.ui.home.OrangeButton
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily

/** Шторка «Автор и чтец»: два поля и кнопка «поменять местами» между ними. */
@Composable
internal fun BookPeopleSheet(
    visible: Boolean,
    authors: List<String>,
    narrators: List<String>,
    strings: BookMenuStrings,
    isDark: Boolean,
    onSave: (authors: String, narrators: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    if (!state.currentState && !state.targetState) return

    MotionBottomSheet(visibleState = state, onDismiss = onDismiss, isDark = isDark, panelModifier = Modifier.fillMaxWidth()) {
        var author by remember { mutableStateOf(BookPeopleOverrides.format(authors)) }
        var narrator by remember { mutableStateOf(BookPeopleOverrides.format(narrators)) }
        Column(
            Modifier.padding(horizontal = 20.dp).padding(top = 28.dp).navigationBarsPadding().imePadding().padding(bottom = 20.dp),
        ) {
            SheetHeader(strings.peopleTitle, strings.peopleHint)
            Spacer(Modifier.height(14.dp))
            PeopleField(author, strings.authorField) { author = it }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .clickable(onClickLabel = strings.swap) {
                            val kept = author
                            author = narrator
                            narrator = kept
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SwapVert, contentDescription = strings.swap, tint = BrandOrange, modifier = Modifier.size(24.dp))
                }
            }
            PeopleField(narrator, strings.narratorField) { narrator = it }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.cancel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 10.dp),
                )
                OrangeButton(strings.save, Modifier.weight(1f)) { onSave(author, narrator) }
            }
        }
    }
}

@Composable
private fun PeopleField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Шторка «Обложка»: варианты сеткой, поиск по своему запросу, файл из галереи. */
@Composable
internal fun BookCoverSheet(
    visible: Boolean,
    picker: CoverPicker,
    strings: BookMenuStrings,
    isDark: Boolean,
    onSearch: (String) -> Unit,
    onPick: (String) -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    if (!state.currentState && !state.targetState) return

    MotionBottomSheet(visibleState = state, onDismiss = onDismiss, isDark = isDark, panelModifier = Modifier.fillMaxWidth()) {
        var query by remember { mutableStateOf(picker.query) }
        Column(Modifier.padding(top = 28.dp).navigationBarsPadding().imePadding()) {
            Column(Modifier.padding(horizontal = 20.dp)) { SheetHeader(strings.coverTitle, strings.coverHint) }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(strings.coverSearchPlaceholder) },
                trailingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(22.dp).clickable { onSearch(query) })
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable(enabled = !picker.saving, onClick = onGallery)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    strings.coverFromGallery,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box(Modifier.weight(1f, fill = false)) {
                when {
                    picker.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.5.dp)
                    }
                    picker.items.isEmpty() -> Text(
                        strings.coverEmpty,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SnProFamily,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(picker.items, key = { it.url }) { option ->
                            Column(
                                Modifier.clickable(enabled = !picker.saving) { onPick(option.url) },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(option.url).crossfade(true).build(),
                                    contentDescription = option.caption,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                                if (option.caption.isNotEmpty()) {
                                    Text(
                                        option.caption,
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
                    }
                }
                if (picker.saving) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.5.dp) }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, hint: String) {
    Text(title, fontFamily = SnProFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    Text(hint, fontFamily = SnProFamily, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
