package com.example.myapplication.audiobooks.ui

import android.content.ComponentName
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

/** Small working entry point until the full shelf design lands in AB-28. */
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

    LaunchedEffect(source) {
        books = source.books()
        unavailableFolders = source.unavailableFolderCount()
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

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(strings.books)
        Button(onClick = { picker.launch(null) }, enabled = !busy) { Text(strings.chooseFolder) }
        message?.let { Text(it) }
        if (unavailableFolders > 0) Text(strings.folderAccessLost)
        if (books.isEmpty()) Text(strings.emptyLibrary)
        for (book in books) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            val manifest = source.refresh(book.variant)
                            resolver.put(manifest)
                            val items = PlaybackQueueBuilder.build(
                                manifest, book.workId, book.narrationId, book.title, "", "",
                            )
                            val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
                            val future = MediaController.Builder(context, token).buildAsync()
                            try {
                                val controller = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
                                controller.setMediaItems(items)
                                controller.prepare()
                                controller.play()
                                message = strings.playbackStarted
                            } finally {
                                MediaController.releaseFuture(future)
                            }
                        }.onFailure {
                            message = if (it is SecurityException) strings.folderAccessLost else strings.sourceUnavailable
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) { Text("${book.title} · ${book.fileCount} · ${strings.play}") }
        }
    }
}
