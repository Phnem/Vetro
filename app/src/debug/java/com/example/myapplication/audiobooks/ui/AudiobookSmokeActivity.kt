package com.example.myapplication.audiobooks.ui

import android.os.Bundle
import android.content.ComponentName
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.workspace.BooksScreen
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.playback.AudiobookPlaybackService
import com.example.myapplication.audiobooks.playback.PlaybackQueueBuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin

/** Debug-only entry point to test the Books page before the main app's account flow. */
class AudiobookSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra("demoPlayer", false)) {
            lifecycleScope.launch { startLocalDemo() }
        }
        setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.fillMaxSize()) {
                    if (intent.getBooleanExtra("openPicker", false)) {
                        LocalBooksPanel(getAudiobookStrings(AppLanguage.RU), modifier = androidx.compose.ui.Modifier.padding(32.dp), autoOpenPicker = true)
                    } else {
                        BooksScreen(AppLanguage.RU, bottomInset = 0.dp)
                    }
                    AudiobookPlayerHost()
                    }
                }
            }
        }
    }

    private suspend fun startLocalDemo() {
        val root = File(filesDir, "ab10-demo")
        withContext(Dispatchers.IO) {
            root.mkdirs()
            writeSilence(File(root, "01.wav"))
            writeSilence(File(root, "02.wav"))
        }
        val source = getKoin().get<LocalFolderSource>()
        val resolver = getKoin().get<ManifestResolver>()
        val book = source.addTree(Uri.fromFile(root)).single()
        val manifest = source.refresh(book.variant)
        resolver.put(manifest)
        val queue = PlaybackQueueBuilder.build(manifest, book.workId, book.narrationId,
            "Vetro Player Demo", "", "")
        val token = SessionToken(applicationContext, ComponentName(applicationContext, AudiobookPlaybackService::class.java))
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        try {
            val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
            controller.setMediaItems(queue)
            controller.prepare()
            controller.play()
        } finally {
            MediaController.releaseFuture(future)
        }
    }

    private fun writeSilence(file: File) {
        val frames = 8_000 * 60
        val data = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray())
        data.putInt(36 + frames * 2)
        data.put("WAVEfmt ".toByteArray())
        data.putInt(16)
        data.putShort(1)
        data.putShort(1)
        data.putInt(8_000)
        data.putInt(16_000)
        data.putShort(2)
        data.putShort(16)
        data.put("data".toByteArray())
        data.putInt(frames * 2)
        repeat(frames) { data.putShort(0) }
        file.writeBytes(data.array())
    }
}
