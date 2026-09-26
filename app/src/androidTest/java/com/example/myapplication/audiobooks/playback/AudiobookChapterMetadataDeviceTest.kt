package com.example.myapplication.audiobooks.playback

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** A one-file, two-chapter book proves notification metadata changes without splitting playback. */
@RunWith(AndroidJUnit4::class)
class AudiobookChapterMetadataDeviceTest {
    @Test fun chapterTitleChangesInsideOnePlayingFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "ab10-two-chapters.wav")
        writeSilence(file)
        val variant = VariantId("ab10-chapters-${UUID.randomUUID()}")
        val manifest = MediaManifest(
            variant = variant,
            tracks = listOf(AudioTrack(0, file.toURI().toString(), "audio/wav", 40_000L, file.length())),
            chapters = listOf(Chapter(0, "First chapter", 0L, 20_000L),
                Chapter(1, "Second chapter", 20_000L, 20_000L)),
            fetchedAt = System.currentTimeMillis(),
            expiresAt = null,
        )
        getKoin().get<ManifestResolver>().put(manifest)
        val items = PlaybackQueueBuilder.build(
            manifest, WorkId(UUID.randomUUID().toString()), NarrationId(UUID.randomUUID().toString()),
            "Two chapters", "Test author", "Test narrator",
        )
        val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            onMain {
                controller.setMediaItems(items)
                controller.prepare()
                controller.play()
            }
            await(15_000) { onMain { controller.isPlaying } }
            assertEquals("First chapter", onMain { controller.currentMediaItem?.mediaMetadata?.title?.toString() })
            onMain { controller.seekTo(21_000L) }
            await(5_000) {
                onMain { controller.currentMediaItem?.mediaMetadata?.title?.toString() == "Second chapter" }
            }
            assertTrue(onMain { controller.isPlaying && controller.currentPosition >= 21_000L })
            assertEquals(1, onMain { controller.currentMediaItem?.mediaMetadata?.extras?.getInt("chapterIndex") })
        } finally {
            onMain { controller.pause() }
            MediaController.releaseFuture(future)
            context.stopService(Intent(context, AudiobookPlaybackService::class.java))
            file.delete()
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

    private fun writeSilence(file: File) {
        val frames = 8_000 * 40
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
