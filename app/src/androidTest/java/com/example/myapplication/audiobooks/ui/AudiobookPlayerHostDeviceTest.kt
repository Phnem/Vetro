package com.example.myapplication.audiobooks.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.playback.AudiobookPlaybackService
import com.example.myapplication.audiobooks.playback.PlaybackResumptionStore
import com.example.myapplication.audiobooks.playback.PlaybackQueueBuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** Exercises the real Books overlay and Media3 controls, using app-private generated audio. */
@RunWith(AndroidJUnit4::class)
class AudiobookPlayerHostDeviceTest {
    @Test fun miniExpandsAndControlsThePlayingService() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch(AudiobookSmokeActivity::class.java)
        val root = File(context.filesDir, "ab10-player-library")
        root.mkdirs()
        writeSilence(File(root, "01.wav"))
        writeSilence(File(root, "02.wav"))
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
            File(root, "cover.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val tree = Uri.fromFile(root)
        val source = LocalFolderSource(context)
        val resolver = getKoin().get<ManifestResolver>()
        val book = source.addTree(tree).single()
        assertEquals("cover.png", book.artworkUri?.lastPathSegment)
        val manifest = source.refresh(book.variant)
        resolver.put(manifest)
        val items = PlaybackQueueBuilder.build(manifest, book.workId, book.narrationId, "AB10 Sample", "", "",
            book.artworkUri?.toString())
        assertEquals(book.artworkUri, items.first().mediaMetadata.artworkUri)
        val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            onMain {
                controller.setMediaItems(items)
                controller.prepare()
                controller.play()
            }
            try {
                await(15_000) { findNode("AB10 Sample") != null }
            } catch (error: AssertionError) {
                File(context.filesDir, "ab10-player-failed.png").outputStream().use { output ->
                    instrumentation.uiAutomation.takeScreenshot()?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
                throw AssertionError("Missing mini; foreground=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}, " +
                    "controllerItem=${onMain { controller.currentMediaItem?.mediaId }}, ui=${dumpUi()}", error)
            }
            clickNode("AB10 Sample")
            await(5_000) { findNode("Свернуть") != null }
            clickNode("Пауза")
            await(5_000) { !onMain { controller.isPlaying } }
            await(5_000) { PlaybackResumptionStore(context).load()?.item?.localConfiguration?.uri?.scheme == "vetro-audio" }
            val saved = PlaybackResumptionStore(context).load() ?: error("Playback snapshot missing")
            assertEquals(2, saved.items.size)
            assertEquals(0, saved.mediaIndex)
            SystemClock.sleep(700)
            File(context.filesDir, "ab10-player-paused.png").outputStream().use { output ->
                instrumentation.uiAutomation.takeScreenshot()?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            onMain { controller.play() }
            await(5_000) { onMain { controller.isPlaying } }
            File(context.filesDir, "ab10-player-full.png").outputStream().use { output ->
                instrumentation.uiAutomation.takeScreenshot()?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            clickNode("Главы")
            await(5_000) { findNodeExact("02") != null }
            clickNode("02", exact = true)
            await(5_000) { onMain { controller.currentMediaItemIndex == 1 } }
            clickNode("Скорость")
            await(5_000) { findNode("1.5×") != null }
            clickNode("1.5×")
            await(5_000) { onMain { controller.playbackParameters.speed == 1.5f } }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await(5_000) { findNode("Свернуть") != null }
            clickNode("Свернуть")
            await(5_000) { findNode("AB10 Sample") != null }
            assertTrue(onMain { controller.currentPosition >= 0L })
        } finally {
            onMain { controller.pause() }
            scenario.close()
            onMain { MediaController.releaseFuture(future) }
            SystemClock.sleep(500)
            context.stopService(Intent(context, AudiobookPlaybackService::class.java))
            source.removeTree(tree)
            root.deleteRecursively()
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findNode("Android App Compatibility") != null) {
                runCatching { clickNode("Don't Show Again") }
            }
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("UI did not reach expected state: ${dumpUi()}", condition())
    }

    private fun clickNode(label: String, exact: Boolean = false) {
        val node = (if (exact) findNodeExact(label) else findNode(label))
            ?: throw AssertionError("Missing UI node: $label; ui=${dumpUi()}")
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertTrue("Could not click $label", clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun findNode(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.contains(label) == true || node.contentDescription?.contains(label) == true) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun findNodeExact(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun dumpUi(): String {
        val found = ArrayList<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || found.size >= 30) return
            node.text?.let { found += it.toString() }
            node.contentDescription?.let { found += it.toString() }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return found.joinToString(" | ")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun writeSilence(file: File) {
        val frames = 8_000 * 20
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
