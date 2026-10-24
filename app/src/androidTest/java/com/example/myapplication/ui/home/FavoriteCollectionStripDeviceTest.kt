package com.example.myapplication.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation.compose.rememberNavController
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import com.example.myapplication.data.local.DevPreferencesKeys
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.example.myapplication.ui.settings.SettingsViewModel
import com.example.myapplication.ui.home.cardmenu.CardActionMenuOverlay
import com.example.myapplication.ui.home.cardmenu.CardMenuTarget
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.material3.MaterialTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalSharedTransitionApi::class)
class FavoriteCollectionStripDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun favoritesHeadingContrastsWithTheDarkCollectionBackground() {
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides androidx.compose.ui.graphics.Color.Black) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        FavoriteCollectionStrip(listOf(fixture(0)), AppLanguage.EN) { _, _, _ -> }
                    }
                }
            }
        }
        val heading = compose.onNode(androidx.compose.ui.test.hasText("Favorite", substring = true))
        heading.assertIsDisplayed()
        compose.waitUntil(8_000) {
            val rendered = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            android.graphics.Color.red(rendered.getPixel(rendered.width / 2, rendered.height / 2)) < 80
        }
        val image = heading.captureToImage().asAndroidBitmap()
        var brightest = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            brightest = maxOf(brightest, android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
        }
        assertTrue("Favorite heading must remain readable without a Surface supplying LocalContentColor: $brightest", brightest > 150)
    }

    @Test fun developerSettingUpdatesHomeAndSurvivesViewModelRecreation() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName.endsWith(".ab07smoke"))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Home requests notifications on entry; keep that OS prompt outside this UI test.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val koin = GlobalContext.get()
        val dataStore = koin.get<DataStore<Preferences>>(named("settings"))
        val key = DevPreferencesKeys.EXPERIMENTAL_GRID
        val previous = dataStore.data.first()[key]
        val store = ViewModelStore()
        val local = koin.get<AnimeLocalDataSource>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images = koin.get<com.example.myapplication.data.repository.ImageStorageRepository>()
        val cover = File(context.cacheDir, "shelf-glass-cover.png")
        val art = android.graphics.Bitmap.createBitmap(240, 360, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(art)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.rgb(245, 85, 45)
        canvas.drawRect(0f, 0f, 120f, 360f, paint)
        paint.color = android.graphics.Color.rgb(30, 145, 245)
        canvas.drawRect(120f, 0f, 240f, 360f, paint)
        cover.outputStream().use { art.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        art.recycle()
        val imageName = images.saveImage(android.net.Uri.fromFile(cover).toString(), "shelf-glass-cover").getOrThrow()
        val favorite = fixture(0).copy(id = "shelf-device-fixture", title = "Shelf device fixture", imageFileName = imageName)
        val regular = List(6) { fixture(it + 1).copy(id = "shelf-regular-$it", title = "Shelf device fixture regular $it", isFavorite = it < 2, imageFileName = imageName) }
        try {
            dataStore.edit { it.remove(key) }
            local.insertAnime(favorite)
            regular.forEach { local.insertAnime(it) }
            val settings = withContext(Dispatchers.Main) {
                koin.get<SettingsViewModel>().also { store.put("settings", it) }
            }
            val home = withContext(Dispatchers.Main) {
                koin.get<HomeViewModel>().also { store.put("home", it) }
            }
            assertEquals(true, home.experimentalGrid.value)
            home.updateSearchQuery("Shelf device fixture")
            compose.setContent {
                OneUiTheme(darkTheme = true) {
                    val nav = rememberNavController()
                    val workspaceBackdrop = rememberLayerBackdrop()
                    com.example.myapplication.ui.shared.components.IosSheetScaffold(
                        sheetVisible = false, onDismiss = {}, sheetContent = {}, content = {
                            Box(Modifier.fillMaxSize().layerBackdrop(workspaceBackdrop)) {
                                SharedTransitionLayout {
                                    val sharedScope = this
                                    AnimatedVisibility(visible = true) {
                                        HomeScreen(nav, home, sharedScope, this, hostedInWorkspace = true)
                                    }
                                }
                            }
                        }
                    )
                }
            }
            settings.setDevExperimentalGrid(true)
            withTimeout(30_000) {
                dataStore.data.first { it[key] == true }
                settings.uiState.first { it.devExperimentalGrid }
                home.experimentalGrid.first { it }
            }
            compose.waitUntil(30_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("favorite_collection_strip"))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithTag("favorite_card_${favorite.id}").assertIsDisplayed()
            android.os.SystemClock.sleep(800)
            fun screenshot(name: String): android.graphics.Bitmap {
                val frame = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val output = File(context.getExternalFilesDir(null), "screens/home_$name.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                return frame
            }
            screenshot("favorites_top")
            compose.onNodeWithTag("home_collection_list").performScrollToIndex(1)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitUntil(8_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("top_dock_surface")).fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            android.os.SystemClock.sleep(800)
            val dockBounds = compose.onNodeWithTag("top_dock_surface").fetchSemanticsNode().boundsInWindow
            val surface = compose.onNodeWithTag("top_dock_surface").captureToImage().asAndroidBitmap()
            screenshot("favorites_dock")
            val underDock = surface.getPixel(surface.width / 2, surface.height / 2)
            val scrollValue = compose.onNodeWithTag("home_collection_list").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Floating top dock must retain its frosted surface over the favorite cover: $underDock dock=$dockBounds scroll=$scrollValue",
                android.graphics.Color.red(underDock) < 205)
            compose.onNodeWithTag("favorite_card_${favorite.id}").performTouchInput { longClick() }
            compose.waitForIdle()
            compose.onNodeWithTag("card_action_row").assertIsDisplayed()
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 10f, height - 200f)) }
            compose.waitUntil(8_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("card_action_row")).fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            android.os.SystemClock.sleep(800)
            val restoredSurface = compose.onNodeWithTag("top_dock_surface").captureToImage().asAndroidBitmap()
            val restoredColor = restoredSurface.getPixel(restoredSurface.width / 2, restoredSurface.height / 2)
            assertTrue("Closing the favorite menu must preserve the live frosted surface: $restoredColor",
                android.graphics.Color.red(restoredColor) < 205)
            screenshot("favorites_dock_after_menu")
            compose.onNodeWithTag("home_collection_list").performScrollToIndex(0)
            compose.waitForIdle()
            android.os.SystemClock.sleep(400)
            val headingBounds = compose.onNode(androidx.compose.ui.test.hasText("Favorite", substring = true)).fetchSemanticsNode().boundsInWindow
            val visibleScreen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            var brightPixels = 0
            for (y in headingBounds.top.toInt() until headingBounds.bottom.toInt()) {
                for (x in headingBounds.left.toInt() until headingBounds.right.toInt()) {
                    if (android.graphics.Color.red(visibleScreen.getPixel(x, y)) > 150) brightPixels++
                }
            }
            assertTrue("Actual Home heading must have visible letters: bounds=$headingBounds brightPixels=$brightPixels", brightPixels > 100)
            val reopenedHome = withContext(Dispatchers.Main) {
                koin.get<HomeViewModel>().also { store.put("reopened_home", it) }
            }
            withTimeout(30_000) { reopenedHome.experimentalGrid.first { it } }
            settings.setDevExperimentalGrid(false)
            withTimeout(30_000) {
                dataStore.data.first { it[key] == false }
                settings.uiState.first { !it.devExperimentalGrid }
                home.experimentalGrid.first { !it }
                reopenedHome.experimentalGrid.first { !it }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("favorite_collection_strip").assertDoesNotExist()
            compose.onNodeWithText(favorite.title).assertIsDisplayed()
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            local.deleteAnime(favorite.id)
            regular.forEach { local.deleteAnime(it.id) }
            images.deleteImage(imageName)
            cover.delete()
            dataStore.edit { if (previous == null) it.remove(key) else it[key] = previous }
        }
    }

    @Test fun lastFavoriteIsReachableAndOpensWithTheCorrectLongPressBounds() {
        val favorites = List(12) { fixture(it) }
        var opened: String? = null
        var held: String? = null
        var bounds: Rect? = null
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    FavoriteCollectionStrip(favorites, AppLanguage.RU) { anime, position, modifier ->
                        FavoriteCollectionCard(
                            state = cardState(anime),
                            releaseYear = 2026,
                            position = position,
                            onClick = { opened = anime.id },
                            onLongClick = { held = anime.id; bounds = it },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Избранное").assertIsDisplayed()
        compose.onNodeWithTag("favorite_collection_strip").performScrollToIndex(11)
        val last = compose.onNodeWithTag("favorite_card_favorite-11")
        last.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("favorite-11", opened) }
        last.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals("favorite-11", held)
            assertNotNull(bounds)
            assertTrue(bounds!!.width > 0f && bounds!!.height > bounds!!.width)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "favorite-collection.png")
        val bitmap = compose.onNodeWithTag("favorite_collection_strip").captureToImage().asAndroidBitmap()
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun removingTheFinalFavoriteRemovesTheWholeSection() {
        val favorites = mutableStateListOf(fixture(0))
        compose.setContent {
            OneUiTheme(darkTheme = false) {
                FavoriteCollectionStrip(favorites, AppLanguage.EN) { anime, position, modifier ->
                    FavoriteCollectionCard(cardState(anime), null, position, {}, {}, modifier)
                }
            }
        }
        compose.onNodeWithText("Favorite").assertIsDisplayed()
        compose.runOnIdle { favorites.clear() }
        compose.onNodeWithText("Favorite").assertDoesNotExist()
        compose.onNodeWithTag("favorite_collection_strip").assertDoesNotExist()
    }

    @Test fun portraitFavoriteMenuKeepsAllFourActionsInsideTheScreen() {
        var edited = false
        var minimumWidth = 0f
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                val backdrop = rememberLayerBackdrop()
                val target = with(LocalDensity.current) {
                    minimumWidth = 258.dp.toPx()
                    CardMenuTarget(
                        state = cardState(fixture(0)), isFavorite = true,
                        boundsInRoot = Rect(200.dp.toPx(), 120.dp.toPx(), 360.dp.toPx(), 350.dp.toPx()),
                        favoritePosition = 1, releaseYear = 2026,
                    )
                }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
                    CardActionMenuOverlay(
                        target = target, visible = true, backdrop = backdrop,
                        bottomInset = 24.dp, topInset = 24.dp,
                        onDismiss = {}, onClosed = {}, onToggleFavorite = {}, onDelete = {},
                        onEdit = { edited = true }, onDetails = {},
                    )
                }
            }
        }
        val row = compose.onNodeWithTag("card_action_row")
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(rowBounds.width >= minimumWidth - 1f)
        assertTrue(rowBounds.left >= screen.left && rowBounds.right <= screen.right)
        row.onChildren()[2].performClick()
        compose.runOnIdle { assertTrue(edited) }
    }

    private fun fixture(index: Int) = Anime(
        id = "favorite-$index", title = "Favorite title $index", episodes = 12,
        rating = 8.5f, imageFileName = null, orderIndex = index, dateAdded = index.toLong(),
        isFavorite = true,
    )

    private fun cardState(anime: Anime) = AnimeCardState(
        id = anime.id, title = anime.title, rating = anime.rating,
        genres = persistentListOf("Fantasy"), episodesLabel = "12 eps.",
        imagePath = null, mediaTypeLabel = "Anime", isFavorite = true,
    )
}
