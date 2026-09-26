package com.example.myapplication.audiobooks.data.local

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.playback.AudiobookPlaybackService
import com.example.myapplication.audiobooks.playback.PlaybackQueueBuilder
import com.example.myapplication.audiobooks.playback.VetroAudioDataSource
import androidx.media3.datasource.DataSpec
import com.phnem.vetro.BuildConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only generated PCM WAVs in the one smoke app's private files. */
@RunWith(AndroidJUnit4::class)
class LocalFolderSourceDeviceTest {
    @Test fun acceptsDedicatedShelfButRejectsGeneralStorage() {
        assertTrue(AudiobookFolderGuard.isAllowed(Uri.parse("content://provider/tree/primary%3AAudiobooks")))
        assertFalse(AudiobookFolderGuard.isAllowed(Uri.parse("content://provider/tree/primary%3ADownload")))
        assertFalse(AudiobookFolderGuard.isAllowed(Uri.parse("content://provider/tree/primary%3A")))
    }

    @Test fun scansNaturallyAndPlaysThroughLogicalUri() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.DEBUG)
        val root = File(context.filesDir, "ab09-library")
        val bookFolder = File(root, "Sample Book")
        bookFolder.mkdirs()
        writeTone(File(bookFolder, "10.wav"))
        writeTone(File(bookFolder, "2.wav"))
        val treeUri = Uri.fromFile(root)
        val source = LocalFolderSource(context)
        try {
            val books = source.addTree(treeUri)
            assertEquals(1, books.size)
            assertEquals(2, books.single().fileCount)
            assertEquals(books.single().workId, source.books().single().workId)
            val manifest = source.refresh(books.single().variant)
            assertEquals(listOf("2.wav", "10.wav"), manifest.tracks.map { Uri.parse(it.url).lastPathSegment })
            assertEquals(2, manifest.chapters.size)
            assertEquals(0L, manifest.chapters[0].startMs)

            val resolver = ManifestResolver(listOf(source))
            val items = PlaybackQueueBuilder.build(
                manifest, books.single().workId, books.single().narrationId, books.single().title, "", "",
            )
            VetroAudioDataSource.factory(context, resolver, com.example.myapplication.audiobooks.torrent.TorrentEngine(context)).createDataSource().apply {
                open(DataSpec(items.first().localConfiguration!!.uri))
                val bytes = ByteArray(4)
                assertEquals(4, read(bytes, 0, 4))
                assertEquals("RIFF", String(bytes))
                close()
            }

            val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            val controller = future.get(15, TimeUnit.SECONDS)
            try {
                onMain {
                    controller.setMediaItems(items)
                    controller.prepare()
                    controller.play()
                }
                await(15_000) { onMain { controller.isPlaying && controller.currentMediaItem?.mediaId == items[0].mediaId } }
                onMain { controller.pause() }
            } finally {
                onMain { controller.release() }
                context.stopService(Intent(context, AudiobookPlaybackService::class.java))
            }
            source.removeTree(treeUri)
            try {
                source.refresh(books.single().variant)
                throw AssertionError("Unregistered folder must not resolve")
            } catch (_: SecurityException) {
                // The URI is no longer an authorized source.
            }
            assertEquals(books.single().workId, source.addTree(treeUri).single().workId)
        } finally {
            source.removeTree(treeUri)
            root.deleteRecursively()
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun writeTone(file: File) {
        val sampleRate = 8_000
        val seconds = 10
        val count = sampleRate * seconds
        val pcmBytes = count * 2
        val bytes = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray())
        bytes.putInt(36 + pcmBytes)
        bytes.put("WAVEfmt ".toByteArray())
        bytes.putInt(16)
        bytes.putShort(1)
        bytes.putShort(1)
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * 2)
        bytes.putShort(2)
        bytes.putShort(16)
        bytes.put("data".toByteArray())
        bytes.putInt(pcmBytes)
        repeat(count) { bytes.putShort(0) }
        file.writeBytes(bytes.array())
    }
}
