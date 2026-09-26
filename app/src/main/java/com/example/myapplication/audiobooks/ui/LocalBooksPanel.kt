package com.example.myapplication.audiobooks.ui

import com.example.myapplication.ui.shared.theme.IosScroll
import android.content.ComponentName
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.phnem.vetro.R
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.myapplication.audiobooks.data.local.LocalBook
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.playback.AudiobookPlaybackService
import com.example.myapplication.audiobooks.playback.PlaybackQueueBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Local-books home: real covers are supplied by the folder source, not demo artwork. */
@Composable
@UnstableApi
fun LocalBooksPanel(
    strings: AudiobookStrings,
    modifier: Modifier = Modifier,
    autoOpenPicker: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val source: LocalFolderSource = koinInject()
    val resolver: ManifestResolver = koinInject()
    val playerState: AudiobookPlayerState = koinInject()
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<LocalBook>>(emptyList()) }
    var unavailableFolders by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expandedShelf by remember { mutableStateOf(false) }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    val homePrefs = remember(context) { context.getSharedPreferences("audiobook_home", android.content.Context.MODE_PRIVATE) }
    val ink = MaterialTheme.colorScheme.onSurface
    val shelfSurface = ink.copy(alpha = 0.06f)

    LaunchedEffect(source) {
        books = source.books()
        unavailableFolders = source.unavailableFolderCount()
        lastPlayed = homePrefs.getString("last_played_variant", null)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { source.addTree(uri) }.onSuccess {
                books = source.books()
                unavailableFolders = source.unavailableFolderCount()
                message = null
            }
                .onFailure {
                    message = when (it) {
                        is IllegalArgumentException -> strings.folderTooGeneral
                        is SecurityException -> strings.folderAccessLost
                        else -> strings.sourceUnavailable
                    }
                }
            busy = false
        }
    }
    LaunchedEffect(autoOpenPicker) {
        if (autoOpenPicker) picker.launch(null)
    }

    fun playBook(book: LocalBook) {
        scope.launch {
            busy = true
            runCatching {
                val manifest = source.refresh(book.variant)
                resolver.put(manifest)
                val items = PlaybackQueueBuilder.build(
                    manifest, book.workId, book.narrationId, book.title, "", "", book.artworkUri?.toString(),
                )
                val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
                val future = MediaController.Builder(context, token).buildAsync()
                try {
                    val controller = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
                    controller.setMediaItems(items)
                    controller.prepare()
                    controller.play()
                    homePrefs.edit().putString("last_played_variant", book.variant.value).apply()
                    playerState.requestExpand()
                    lastPlayed = book.variant.value
                    message = strings.playbackStarted
                } finally {
                    MediaController.releaseFuture(future)
                }
            }.onFailure {
                message = if (it is SecurityException) strings.folderAccessLost else strings.sourceUnavailable
            }
            busy = false
        }
    }

    val isDark = com.example.myapplication.ui.shared.theme.isAppInDarkTheme()
    val muted = ink.copy(alpha = 0.6f)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.myLibrary, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                    if (books.isNotEmpty()) {
                        Text(books.size.toString(), color = muted, fontFamily = SnProFamily, fontSize = 15.sp)
                    }
                }
                PanelButton(R.drawable.ph_folder_plus, strings.chooseFolder, isDark, enabled = !busy) { picker.launch(null) }
                if (onClose != null) {
                    Spacer(Modifier.width(10.dp))
                    PanelButton(R.drawable.ph_x, strings.collapse, isDark, onClick = onClose)
                }
            }
        }
        val notice = message ?: if (unavailableFolders > 0) strings.folderAccessLost else null
        if (notice != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(notice, color = muted, fontFamily = SnProFamily, fontSize = 13.sp)
            }
        }
        if (books.isEmpty()) {
            // Пустое состояние называет причину и одно действие, которое его заполняет.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(IosDesign.groupRowBackground(isDark))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(painterResource(R.drawable.ph_books), null, tint = muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(strings.emptyLibrary, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(if (busy) BrandOrange.copy(alpha = 0.4f) else BrandOrange)
                            .clickable(enabled = !busy) { picker.launch(null) }
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(strings.chooseFolder, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
        } else {
            items(books, key = { it.variant.value }) { book ->
                Column(Modifier.clickable(enabled = !busy) { playBook(book) }) {
                    LocalCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Spacer(Modifier.height(8.dp))
                    Text(book.title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (book.variant.value == lastPlayed) strings.continueListening else book.fileCount.toString(),
                        color = if (book.variant.value == lastPlayed) BrandOrange else muted,
                        fontFamily = SnProFamily, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** Круглая кнопка панели: полупрозрачная над градиентом раздела, иконка Phosphor. */
@Composable
private fun PanelButton(icon: Int, description: String, isDark: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(IosDesign.groupRowBackground(isDark))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = if (isDark) Color.White else Color(0xFF111111), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LocalCover(book: LocalBook, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        if (book.artworkUri != null) {
            AsyncImage(model = book.artworkUri, contentDescription = book.title, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop)
        } else {
            Text(book.title.take(40), color = Color.White.copy(alpha = 0.72f), fontFamily = SnProFamily, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp),
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}
