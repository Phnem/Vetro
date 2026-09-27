package com.example.myapplication.media

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.ui.StreamPlayerActivity
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Настоящий плеер на публичном тестовом HLS: меню «Субтитры» — корень и разделы OpenSubtitles и
 * «Создать на устройстве». Скриншоты экрана (с кадром видео) — в files/screens тестового приложения.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleMenuDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 180_000) fun subtitleMenuSections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = VetroVideo(url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", label = "1080p", sourceName = "Test")
        val intent = StreamPlayerActivity.intent(context, video, animeId = "device-test", animeTitle = "Big Buck Bunny", episode = 1)
        // Телефон может быть заблокирован: окно плеера показывается поверх экрана блокировки (флаг —
        // до создания окна), сама блокировка не снимается.
        val overLock = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.PRE_ON_CREATE) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(overLock)
        ActivityScenario.launch<StreamPlayerActivity>(intent).use {
            Thread.sleep(4_000)
            val subtitles = hasContentDescription("Субтитры").or(hasContentDescription("Subtitles"))
            compose.waitUntil(10_000) { compose.onAllNodes(subtitles).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(subtitles).performClick()
            compose.waitForIdle()
            Thread.sleep(800)
            shot("subtitles_root.png")
            val whisper = hasText("Создать на устройстве").or(hasText("Create on device"))
            compose.onNode(whisper).performClick()
            compose.waitForIdle()
            Thread.sleep(800)
            shot("subtitles_whisper.png")
            compose.onNode(hasText("‹ Назад").or(hasText("‹ Back"))).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("OpenSubtitles").performClick()
            compose.waitForIdle()
            Thread.sleep(800)
            shot("subtitles_opensubtitles.png")
        }
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(overLock)
    }

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        Log.i("SubtitleMenuDevice", "screenshot $name")
    }
}
