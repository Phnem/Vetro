package com.example.myapplication.ui.details

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.media.progress.TitleTrackingPreferences
import com.example.myapplication.ui.shared.components.IosSheetScaffold
import com.example.myapplication.ui.shared.theme.OneUiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DetailsSharedSheetDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(35)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun ratingOptionsStayInABottomSheetAboveTheDetailsPage() {
        var picked: Boolean? = null
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                IosSheetScaffold(sheetVisible = true, onDismiss = {}, sheetHeightFraction = null,
                    content = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { Text("Details page", color = MaterialTheme.colorScheme.onBackground) } },
                    sheetContent = { Picker(DetailsPickerState.RatingMode(false), { picked = it }) })
            }
        }
        val header = compose.onNodeWithText("Пользовательский рейтинг").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Mode choices must open near the bottom", header.top > root.height / 2f)
        compose.onNodeWithText("Details page").assertExists()
        screenshot("details_ios_rating_sheet.png")
        compose.onNodeWithText("Manual rating").performClick()
        compose.runOnIdle { assertEquals(true, picked) }
    }

    @Test fun dateModeChangesToTheCalendarInsideTheSameIosSheet() {
        var state by mutableStateOf<DetailsPickerState>(DetailsPickerState.DateMode(TitleTrackingPreferences()))
        var saved: Pair<LocalDate, LocalDate?>? = null
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                IosSheetScaffold(sheetVisible = true, onDismiss = {}, sheetHeightFraction = null,
                    content = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { Text("Details page", color = MaterialTheme.colorScheme.onBackground) } },
                    sheetContent = {
                        Picker(state, onDateMode = {
                            state = DetailsPickerState.WatchDates(LocalDate.of(2026, 10, 10), null)
                        }, onSave = { start, finish -> saved = start to finish })
                    })
            }
        }
        compose.onNodeWithText("Ручной ввод").performClick()
        compose.onNodeWithText("10").performClick()
        compose.onNodeWithText("20").performClick()
        compose.onNodeWithText("Сохранить").performScrollTo()
        screenshot("details_ios_calendar_sheet.png")
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 10) to LocalDate.of(2026, 10, 20), saved) }
    }

    @Composable private fun Picker(
        state: DetailsPickerState,
        onRatingMode: (Boolean) -> Unit = {},
        onDateMode: (Boolean) -> Unit = {},
        onSave: (LocalDate, LocalDate?) -> Unit = { _, _ -> },
    ) {
        DetailsPickerContent(state, ru = true, isDark = true, onPickCover = {}, onPickSource = {},
            onNoneOfThese = {}, onSearch = {}, onPickStatus = {}, onPickRatingMode = onRatingMode,
            onPickDateMode = onDateMode, onSaveWatchDates = onSave)
    }

    private fun screenshot(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
