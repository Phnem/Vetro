package com.example.myapplication.ui.details

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.manga.data.MangaBinding
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.ui.ChaptersContent
import com.example.myapplication.manga.ui.MangaLibraryUiState
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.example.myapplication.ui.shared.theme.IosDesign
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class SectionMotionDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(45)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Before fun isolatedAppOnly() {
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName.endsWith(".ab07smoke"))
    }

    @Test fun lastSeasonHasItsOwnSpatialRevealWithoutAFollowingSeason() = runBlocking {
        val local = GlobalContext.get().get<AnimeLocalDataSource>()
        val store = GlobalContext.get().get<SeasonEpisodesStore>()
        val id = "section-motion-${UUID.randomUUID()}"
        val anime = Anime(id, "Motion fixture", episodes = 12, rating = 7f,
            imageFileName = null, orderIndex = 0, dateAdded = 1)
        try {
            local.insertAnime(anime)
            store.put(SeasonEpisodesEntry(id, (1..3).map { SeasonInfo(it, 4, source = "device-test") }, complete = true))
            compose.setContent {
                OneUiTheme(darkTheme = true) { DetailsScreen(rememberNavController(), id, openEpisodes = true) }
            }
            val season = hasText("Season 3") or hasText("Сезон 3")
            compose.waitUntil(10_000) { compose.onAllNodes(season).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(season).performScrollTo()
            compose.mainClock.autoAdvance = false
            compose.onNode(season).performClick()
            val row = hasText("Episode 1") or hasText("Серия 1")
            assertSpatialReveal(row, "season")
            compose.mainClock.autoAdvance = false
            compose.onNode(season).performClick()
            assertSpatialCollapse(row)
        } finally { local.deleteAnime(id) }
    }

    @Test fun lastVolumeRevealsItsFirstChapterFromUnderItsHeader() {
        val chapters = (1..3).flatMap { volume -> (1..4).map { number ->
            MangaChapter(MangaSourceId("fixture"), "manga", "$volume-$number",
                number = (volume * 10 + number).toDouble(), volume = volume.toDouble())
        } }
        val state = MangaLibraryUiState.Chapters(
            binding = MangaBinding("fixture", "fixture", "manga", "Volume motion"),
            chapters = chapters, availableLanguages = emptyList(), progress = emptyMap())
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(IosDesign.screenGradient(true))) {
                ChaptersContent(state, ru = true, onOpen = {}, onToggleRead = { _, _ -> },
                    onToggleDownload = {}, onLanguage = {}, onRefresh = {}, onChangeSource = {})
                }
            }
        }
        compose.onNode(hasText("Том 1") and hasClickAction()).performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("Том 1") and hasClickAction()).performClick()
        assertSpatialReveal(hasText("Глава 14"), "volume")
    }

    @Test fun bottomVolumeMakesRoomForItsFirstChapterAboveTheDock() {
        showVolumes(30, 4)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Том 1") and hasClickAction())
        compose.onNode(hasText("Том 1") and hasClickAction()).performClick()
        compose.onNodeWithText("Глава 1004").assertIsDisplayed()
    }

    @Test fun longVolumesStayLazyAndTheirFinalChapterRemainsReachable() {
        showVolumes(1, 500)
        compose.onNode(hasText("Том 1") and hasClickAction()).performClick()
        compose.onNodeWithText("Глава 1500").assertIsDisplayed()
        assertTrue("A 500-chapter volume must not compose every row",
            compose.onAllNodes(hasText("Глава ", substring = true)).fetchSemanticsNodes().size < 40)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Глава 1001"))
        compose.onNodeWithText("Глава 1001").assertIsDisplayed()
    }

    @Test fun interruptedRevealCanCloseAndReopenWithoutLeavingInvisibleRows() {
        showVolumes(1, 12)
        compose.mainClock.autoAdvance = false
        val header = compose.onNode(hasText("Том 1") and hasClickAction())
        header.performClick()
        compose.mainClock.advanceTimeBy(64)
        header.performClick()
        compose.mainClock.advanceTimeBy(64)
        header.performClick()
        compose.mainClock.advanceTimeBy(1200)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Глава 1012").assertIsDisplayed()
    }

    @Test fun closingVolumeRetractsItsRowsBeforeRemovingThem() {
        showVolumes(1, 4)
        val header = compose.onNode(hasText("Том 1") and hasClickAction())
        header.performClick()
        val row = hasText("Глава 1004")
        bounds(row)
        compose.mainClock.autoAdvance = false
        header.performClick()
        assertSpatialCollapse(row)
    }

    private fun assertSpatialCollapse(row: SemanticsMatcher) {
        compose.mainClock.advanceTimeBy(48)
        val early = bounds(row)
        compose.mainClock.advanceTimeBy(144)
        val later = bounds(row)
        assertTrue("Closing must retract rows smoothly: early=$early later=$later",
            later.width < early.width - 0.5f && later.top < early.top - 0.5f)
        compose.mainClock.advanceTimeBy(1200)
        compose.onNode(row).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun openingMiddleVolumeNeverRewindsUnderItsHeader() {
        showVolumes(3, 4)
        val header = compose.onNode(hasText("Том 2") and hasClickAction())
        header.performScrollTo()
        compose.mainClock.autoAdvance = false
        header.performClick()
        val samples = mutableListOf<Pair<Float, Float>>()
        repeat(16) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            val row = compose.onNodeWithText("Глава 2004").fetchSemanticsNode().boundsInRoot
            val head = header.fetchSemanticsNode().boundsInRoot
            samples += row.width to (row.top - head.bottom)
        }
        assertTrue("Opening must not rewind to its collapsed pose: $samples",
            samples.zipWithNext().all { (a, b) -> b.first >= a.first - 1f && b.second >= a.second - 1f })
        compose.mainClock.autoAdvance = true
    }

    @Test fun reopeningDuringCollapseContinuesFromTheVisiblePose() {
        showVolumes(1, 4)
        val header = compose.onNode(hasText("Том 1") and hasClickAction())
        header.performClick()
        val openWidth = bounds(hasText("Глава 1004")).width
        compose.mainClock.autoAdvance = false
        header.performClick()
        compose.mainClock.advanceTimeBy(32)
        header.performClick()
        compose.mainClock.advanceTimeBy(32)
        val reopenedWidth = bounds(hasText("Глава 1004")).width
        assertTrue("Reopening must continue the visible pose, not snap back to zero: open=$openWidth reopened=$reopenedWidth",
            reopenedWidth >= openWidth * 0.97f)
        compose.mainClock.advanceTimeBy(1200)
        compose.mainClock.autoAdvance = true
    }

    private fun showVolumes(volumes: Int, chaptersPerVolume: Int) {
        val chapters = (1..volumes).flatMap { volume -> (1..chaptersPerVolume).map { number ->
            MangaChapter(MangaSourceId("fixture"), "manga", "$volume-$number",
                number = (volume * 1000 + number).toDouble(), volume = volume.toDouble())
        } }
        val state = MangaLibraryUiState.Chapters(MangaBinding("fixture", "fixture", "manga", "Motion fixture"),
            chapters, availableLanguages = emptyList(), progress = emptyMap())
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(IosDesign.screenGradient(true))) {
                ChaptersContent(state, ru = true, onOpen = {}, onToggleRead = { _, _ -> },
                    onToggleDownload = {}, onLanguage = {}, onRefresh = {}, onChangeSource = {})
                }
            }
        }
    }

    private fun assertSpatialReveal(row: SemanticsMatcher, name: String) {
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        val early = bounds(row)
        screenshot("${name}_048.png")
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        val later = bounds(row)
        screenshot("${name}_208.png")
        assertTrue("A section must reveal rows spatially, even when no next header can move: early=$early later=$later",
            later.width > early.width + 0.5f && later.top > early.top + 0.5f)
        compose.mainClock.advanceTimeBy(1000)
        screenshot("${name}_settled.png")
        compose.mainClock.autoAdvance = true
    }

    private fun bounds(row: SemanticsMatcher): Rect = compose.onNode(row)
        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private fun screenshot(name: String) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "screens/section_$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
