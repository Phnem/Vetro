package com.example.myapplication.audiobooks.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a generated PCM WAV, so this test never depends on an external audiobook site. */
@RunWith(AndroidJUnit4::class)
class AudiobookPlaybackServiceDeviceTest {
    @Test
    fun servicePlaysInBackgroundAndRestoresLastLocalItem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = File(context.filesDir, "ab07-smoke.wav")
        writeTone(sample, durationSeconds = 40)
        try {
        val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
        val firstFuture = MediaController.Builder(context, token).buildAsync()
        val first = firstFuture.get(15, TimeUnit.SECONDS)
        try {
            onMain {
                first.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("ab07-local")
                        .setUri(sample.toURI().toString())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("AB07 Smoke")
                                .setAlbumTitle("Vetro Audiobooks")
                                .setArtist("Synthetic tone")
                                .build(),
                        )
                        .build(),
                )
                first.prepare()
                first.play()
            }
            await(15_000) { onMain { first.isPlaying && first.currentPosition > 0L } }
            assertEquals(15_000L, onMain { first.seekBackIncrement })
            assertEquals(30_000L, onMain { first.seekForwardIncrement })
            val manager = context.getSystemService(NotificationManager::class.java)
            await(10_000) { manager.activeNotifications.any { it.packageName == context.packageName } }
            SystemClock.sleep(1_500)
            onMain { first.pause() }
            await(5_000) { (PlaybackResumptionStore(context).load()?.positionMs ?: 0L) > 0L }
        } finally {
            onMain { first.release() }
        }

        context.stopService(Intent(context, AudiobookPlaybackService::class.java))
        SystemClock.sleep(500)
        val resumedFuture: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        val resumed = resumedFuture.get(15, TimeUnit.SECONDS)
        try {
            onMain { resumed.play() }
            await(15_000) { onMain { resumed.currentMediaItem?.mediaId == "ab07-local" && resumed.isPlaying } }
            assertNotNull(onMain { resumed.currentMediaItem })
            assertTrue(onMain { resumed.currentPosition > 0L })
            onMain { resumed.pause() }
        } finally {
            onMain { resumed.release() }
            context.stopService(Intent(context, AudiobookPlaybackService::class.java))
        }
        } finally {
            sample.delete()
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

    private fun writeTone(file: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val count = sampleRate * durationSeconds
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
        repeat(count) { index ->
            val value = (kotlin.math.sin(2.0 * Math.PI * 220.0 * index / sampleRate) * 1_200).toInt()
            bytes.putShort(value.toShort())
        }
        file.writeBytes(bytes.array())
    }
}
