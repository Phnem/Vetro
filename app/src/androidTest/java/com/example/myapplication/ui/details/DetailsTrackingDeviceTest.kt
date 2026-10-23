package com.example.myapplication.ui.details

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalLayoutApi::class)
class DetailsTrackingDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(35)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun manualCalendarSelectsAndSavesDateRange() {
        var saved: Pair<LocalDate, LocalDate?>? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    WatchDatesContent(DetailsPickerState.WatchDates(LocalDate.of(2026, 10, 10), null),
                        ru = true, isDark = true, onSave = { start, finish -> saved = start to finish })
                }
            }
        }
        compose.onNodeWithText("10").performClick()
        compose.onNodeWithText("20").performClick()
        screenshot("watch_dates_calendar.png")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 10) to LocalDate.of(2026, 10, 20), saved) }
    }

    @Test fun invertedDateRangeCannotBeSavedAndFinishCanBeCleared() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    WatchDatesContent(DetailsPickerState.WatchDates(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 10)),
                        ru = true, isDark = false, onSave = { _, _ -> fail("Inverted dates cannot be saved") })
                }
            }
        }
        compose.onNodeWithText("Сохранить").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Дата окончания не может быть раньше начала").assertExists()
        compose.onNodeWithText("Ещё смотрю — убрать окончание").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить").performScrollTo().assertIsEnabled()
    }

    @Test fun pillsWrapOnNarrowScreensAndModeSelectionIsClickable() {
        var picked: Boolean? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                    FlowRow(Modifier.width(280.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrackingChip("Auto", "Режим рейтинга", Icons.Rounded.Star, true) {}
                        TrackingChip("10.10.26–20.10.26", "Даты просмотра", Icons.Rounded.Schedule, true) {}
                    }
                    TrackingModeContent(true, false, ru = true, isDark = true, onPick = { picked = it })
                }
            }
        }
        compose.onNodeWithContentDescription("Даты просмотра: 10.10.26–20.10.26").assertIsDisplayed()
        screenshot("watch_rating_modes.png")
        compose.onNodeWithText("Manual rating").performClick()
        compose.runOnIdle { assertEquals(true, picked) }
    }

    private fun screenshot(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
