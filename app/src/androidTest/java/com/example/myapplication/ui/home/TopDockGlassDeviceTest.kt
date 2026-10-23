package com.example.myapplication.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.GlassActionDock
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.back.ProvideExteraBack
import com.example.myapplication.ui.shared.components.IosSheetScaffold
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.example.myapplication.utils.getStrings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class TopDockGlassDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun topDockKeepsItsFrostedBackdropAfterSheetDismissal() {
        val shown = mutableStateOf(false)
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                ProvideExteraBack(enabled = true) {
                    val backdrop = rememberLayerBackdrop()
                    IosSheetScaffold(sheetVisible = shown.value, onDismiss = { shown.value = false },
                        edgeContentLayer = backdrop.graphicsLayer, sheetHeightFraction = null,
                        content = {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                    drawRect(Color(0xFFEE4020), size = Size(size.width / 2, size.height))
                                    drawRect(Color(0xFF2060EE), topLeft = Offset(size.width / 2, 0f))
                                }
                                GlassActionDock(backdrop, true, getStrings(AppLanguage.EN), emptyList(),
                                    emptyList(), {}, {}, {}, modifier = Modifier.testTag("top_dock_glass"))
                            }
                        }, sheetContent = { Box(Modifier.height(180.dp)) { Text("Sheet fixture") } })
                }
            }
        }
        compose.waitForIdle()
        fun frame(name: String): android.graphics.Bitmap {
            val bounds = compose.onNodeWithTag("top_dock_glass").fetchSemanticsNode().boundsInWindow
            compose.waitUntil(8_000) {
                val rendered = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val color = rendered.getPixel(20, rendered.height / 3)
                android.graphics.Color.red(color) > android.graphics.Color.blue(color) + 30
            }
            android.os.SystemClock.sleep(200)
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val bitmap = android.graphics.Bitmap.createBitmap(screen, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.getExternalFilesDir(null), "screens/dock_$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        val before = frame("before")
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("Sheet fixture").assertIsDisplayed()
        frame("during")
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        val after = frame("after")
        // A flat fallback loses the colored scene. Sample the free glass beneath the icons.
        val x = before.width / 4
        val y = before.height - 10
        val initial = before.getPixel(x, y)
        val restored = after.getPixel(x, y)
        assertTrue("The dock must sample the colored scene before opening a sheet",
            android.graphics.Color.red(initial) - android.graphics.Color.blue(initial) > 35)
        assertTrue("Dismissing a sheet must preserve frosted backdrop, before=$initial after=$restored",
            kotlin.math.abs(android.graphics.Color.red(initial) - android.graphics.Color.red(restored)) < 12 &&
                kotlin.math.abs(android.graphics.Color.blue(initial) - android.graphics.Color.blue(restored)) < 12)
    }
}
