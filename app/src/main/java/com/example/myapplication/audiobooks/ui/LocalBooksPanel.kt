package com.example.myapplication.audiobooks.ui

import android.content.ComponentName
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
fun LocalBooksPanel(strings: AudiobookStrings, modifier: Modifier = Modifier, autoOpenPicker: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val source: LocalFolderSource = koinInject()
    val resolver: ManifestResolver = koinInject()
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

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(strings.books, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.size(46.dp).clip(CircleShape).background(shelfSurface)
                .clickable(enabled = !busy) { picker.launch(null) }
                .semantics { contentDescription = strings.chooseFolder }, contentAlignment = Alignment.Center) {
                Text("+", color = ink, fontSize = 26.sp)
            }
        }
        message?.let { Text(it, fontSize = 13.sp) }
        if (unavailableFolders > 0) Text(strings.folderAccessLost, fontSize = 13.sp)
        if (books.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(shelfSurface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.emptyLibrary, color = ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Button(onClick = { picker.launch(null) }, enabled = !busy) { Text(strings.chooseFolder) }
            }
        } else {
            val recent = books.firstOrNull { it.variant.value == lastPlayed }
            if (recent != null) {
                Text(strings.continueListening, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(shelfSurface).clickable(enabled = !busy) { playBook(recent) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    LocalCover(recent, Modifier.size(width = 94.dp, height = 134.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(recent.title, color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("${recent.fileCount} · ${strings.play}", color = ink.copy(alpha = 0.66f), fontSize = 12.sp)
                        Text("▶  ${strings.continueListening}", color = Color(0xFFFF7735), fontSize = 13.sp)
                    }
                }
            }
            Text(strings.shelves, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(shelfSurface).clickable { expandedShelf = !expandedShelf }
                .animateContentSize().padding(top = 18.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.myLibrary, color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(books.size.toString(), color = ink.copy(alpha = 0.58f), fontSize = 13.sp)
                }
                Text(if (expandedShelf) "⌃" else "⌄", color = ink.copy(alpha = 0.55f), fontSize = 18.sp)
                BoxWithConstraints(Modifier.fillMaxWidth().height(156.dp)) {
                    val visible = books.take(3)
                    val cardWidth = 91.dp
                    val center = (maxWidth - cardWidth) / 2
                    visible.forEachIndexed { index, book ->
                        val x by animateDpAsState(
                            if (expandedShelf) (maxWidth - cardWidth * visible.size) / 2 + cardWidth * index
                            else center + 37.dp * (index - (visible.size - 1) / 2f),
                            label = "shelf-cover-x",
                        )
                        val rotation by animateFloatAsState(
                            if (expandedShelf) 0f else (index - (visible.size - 1) / 2f) * 7f,
                            label = "shelf-cover-angle",
                        )
                        LocalCover(book, Modifier.offset(x = x, y = 7.dp).size(width = cardWidth, height = 139.dp)
                            .graphicsLayer { rotationZ = rotation })
                    }
                }
                if (expandedShelf) {
                    books.forEach { book ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { playBook(book) }
                            .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LocalCover(book, Modifier.size(width = 44.dp, height = 62.dp))
                            Text(book.title, color = ink, modifier = Modifier.weight(1f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("▶", color = Color(0xFFFF7735))
                        }
                    }
                }
            }
            Button(onClick = { picker.launch(null) }, enabled = !busy) { Text(strings.chooseFolder) }
        }
    }
}

@Composable
private fun LocalCover(book: LocalBook, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF48525A)),
        contentAlignment = Alignment.Center) {
        if (book.artworkUri != null) {
            AsyncImage(model = book.artworkUri, contentDescription = book.title, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop)
        } else {
            Text(book.title.take(24), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
