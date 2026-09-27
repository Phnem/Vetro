package com.example.myapplication.ui.details

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.domain.enrichment.title.NextRelease
import com.example.myapplication.domain.enrichment.title.ReleaseTrack
import com.example.myapplication.network.enrichment.EnrichmentSource
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Секция «Следующий эпизод через» на устройстве, на реальных часах: текст, тик каждую секунду,
 * исчезновение без отрицательных значений и ровно одна просьба обновить расписание.
 */
@RunWith(AndroidJUnit4::class)
class NextReleaseSectionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ticksEverySecondThenDisappearsAndAsksForRefreshOnce() {
        val elapsed = AtomicInteger()
        val at = Instant.ofEpochMilli(System.currentTimeMillis() + 3_600)
        val release = NextRelease(ReleaseTrack.RU, 26, at, exactTime = false, source = EnrichmentSource.ANILIBRIA)
        // delay в LaunchedEffect идёт по виртуальным часам теста, значение — по часам устройства:
        // двигаем виртуальные ровно на столько, сколько прошло реального времени.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.background(MaterialTheme.colorScheme.background).padding(20.dp).fillMaxWidth()) {
                    Text("[плашки источников]")
                    NextReleaseSection(release = release, ru = true, onElapsed = { elapsed.incrementAndGet() })
                    Text("Информация")
                }
            }
        }
        compose.onNodeWithText("Следующий эпизод через:").assertExists()
        val first = currentValue()
        Log.i(TAG, "first=$first")
        saveScreenshot("next_release_section.png")
        realTime(1_100)
        val second = currentValue()
        Log.i(TAG, "second=$second")
        assertNotEquals("значение сменилось через секунду", first, second)

        // Дождаться выхода: секция исчезает, отрицательного значения не было, обновление — один раз.
        repeat(12) { if (elapsed.get() == 0) realTime(500) }
        compose.waitForIdle()
        compose.onNodeWithText("Следующий эпизод через:").assertDoesNotExist()
        realTime(1_500)
        assertEquals(1, elapsed.get())
        compose.onNodeWithText("Информация").assertExists()
    }

    private fun realTime(ms: Long) {
        Thread.sleep(ms)
        compose.mainClock.advanceTimeBy(ms)
    }

    private fun currentValue(): String {
        compose.waitForIdle()
        return compose.onNodeWithText("сек", substring = true).fetchSemanticsNode()
            .config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.joinToString { it.text }.orEmpty()
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot ${File(dir, name).absolutePath}")
    }

    private companion object {
        const val TAG = "NextReleaseDevice"
    }
}
