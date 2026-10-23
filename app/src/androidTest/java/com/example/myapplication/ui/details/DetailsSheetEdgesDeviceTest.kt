package com.example.myapplication.ui.details

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.ui.shared.back.ProvideExteraBack
import com.example.myapplication.ui.shared.theme.OneUiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Uses the real Details screen and Extera host: the default sheet-only fixture misses this bug. */
@RunWith(AndroidJUnit4::class)
class DetailsSheetEdgesDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(60)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun darkDetailsRecedesIntoItsBackgroundInExtera() = checkCoverEdges(dark = true, extera = true)
    @Test fun lightDetailsRecedesIntoItsBackgroundInExtera() = checkCoverEdges(dark = false, extera = true)
    @Test fun darkScrolledDetailsKeepTheirOwnEdgeColors() = checkCoverEdges(dark = true, extera = true, scrolled = true)
    @Test fun lightScrolledDetailsKeepTheirOwnEdgeColors() = checkCoverEdges(dark = false, extera = true, scrolled = true)
    @Test fun defaultDetailsCoverStaysEdgeToEdge() = checkCoverEdges(dark = true, extera = false)

    private fun checkCoverEdges(dark: Boolean, extera: Boolean, scrolled: Boolean = false) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".ab07smoke"))
        val local = GlobalContext.get().get<AnimeLocalDataSource>()
        val images = GlobalContext.get().get<ImageStorageRepository>()
        val id = "sheet-edges-${UUID.randomUUID()}"
        val cover = File(context.cacheDir, "$id.png")
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 220, 150))
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = Color.rgb(30, 210, 210)
        canvas.drawRect(0f, 0f, 60f, 300f, paint)
        paint.color = Color.rgb(35, 150, 240)
        canvas.drawRect(140f, 0f, 200f, 300f, paint)
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val imageName = images.saveImage(Uri.fromFile(cover).toString(), id).getOrThrow()
        val anime = Anime(id, "Sheet edges fixture", episodes = 12, rating = 8f,
            imageFileName = imageName, orderIndex = 0, dateAdded = 1, watchStatus = WatchStatus.PLANNED)
        try {
            local.insertAnime(anime)
            compose.setContent {
                OneUiTheme(darkTheme = dark) {
                    ProvideExteraBack(enabled = extera) {
                        DetailsScreen(rememberNavController(), id)
                    }
                }
            }
            val ratingChip = hasContentDescription("Rating mode: Auto") or
                hasContentDescription("Режим рейтинга: Auto")
            compose.waitUntil(10_000) { compose.onAllNodes(ratingChip).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) {
                val frame = compose.onRoot().captureToImage().asAndroidBitmap()
                Color.green(frame.getPixel(frame.width / 2, frame.height / 8)) > 150
            }
            if (scrolled) {
                val verticalPage = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) and
                    hasAnyDescendant(ratingChip)
                compose.onNode(verticalPage).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
                    it(0f, 450f)
                }
                compose.waitForIdle()
            }
            val restingWidth = compose.onNode(ratingChip).fetchSemanticsNode().boundsInRoot.width
            compose.onNode(ratingChip).performClick()
            compose.onNodeWithText("Auto rating").assertIsDisplayed()
            val recessedWidth = compose.onNode(ratingChip).fetchSemanticsNode().boundsInRoot.width
            val scale = recessedWidth / restingWidth
            assertTrue("Sheet must preserve the requested recession: extera=$extera scale=$scale",
                if (extera) scale in 0.90f..0.96f else abs(scale - 1f) < 0.01f)
            val frame = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(context.getExternalFilesDir(null), "screens/details_sheet_edges_${dark}_${extera}_${scrolled}.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (y in listOf(frame.height / 8, frame.height / 2)) {
                val inset = frame.width * (1f - scale) / 2f
                val probes = listOf(
                    2 to frame.width / 20,
                    inset.toInt().coerceIn(0, frame.width - 1) to frame.width / 20,
                    frame.width - 3 to frame.width * 19 / 20,
                    (frame.width - inset).toInt().coerceIn(0, frame.width - 1) to frame.width * 19 / 20,
                )
                for ((edgeX, adjacentX) in probes) {
                    val edge = frame.getPixel(edgeX, y)
                    val adjacent = frame.getPixel(adjacentX, y)
                    assertTrue("Margins must continue this page's edge colors, including after scrolling: x=$edgeX y=$y edge=$edge adjacent=$adjacent",
                        abs(Color.red(edge) - Color.red(adjacent)) < 8 &&
                            abs(Color.green(edge) - Color.green(adjacent)) < 8 &&
                            abs(Color.blue(edge) - Color.blue(adjacent)) < 8)
                }
            }
        } finally {
            local.deleteAnime(id)
            images.deleteImage(imageName)
            cover.delete()
        }
    }
}
