package com.example.myapplication.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.ui.shared.components.IosSheetScaffold
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.example.myapplication.utils.getStrings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

@OptIn(ExperimentalSharedTransitionApi::class)
class CollectionDeletionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletingFromTheCardRequiresConfirmation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        assumeTrue(packageName.endsWith(".ab07smoke"))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val koin = GlobalContext.get()
        val anime = Anime(
            id = "collection-delete-${System.nanoTime()}",
            title = "Collection deletion fixture",
            episodes = 12,
            rating = 7f,
            imageFileName = null,
            orderIndex = 0,
            dateAdded = System.currentTimeMillis(),
        )
        val local = koin.get<AnimeLocalDataSource>()
        val models = ViewModelStore()
        try {
            local.insertAnime(anime)
            val home = withContext(Dispatchers.Main) {
                koin.get<HomeViewModel>().also { models.put("home", it) }
            }
            compose.setContent {
                val nav = rememberNavController()
                val backdrop = rememberLayerBackdrop()
                OneUiTheme(darkTheme = true) {
                    IosSheetScaffold(
                        sheetVisible = false,
                        onDismiss = {},
                        sheetContent = {},
                        content = {
                            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                SharedTransitionLayout {
                                    val sharedScope = this
                                    AnimatedVisibility(visible = true) {
                                        HomeScreen(nav, home, sharedScope, this, hostedInWorkspace = true)
                                    }
                                }
                            }
                        },
                    )
                }
            }
            compose.waitUntil("the fixture is in the home collection", 15_000) {
                home.animeListFlow.value.any { it.id == anime.id }
            }

            val strings = getStrings(home.uiLanguage.value)
            val title = compose.onNodeWithText(anime.title)
            val trash = compose.onNodeWithContentDescription(strings.deleteConfirm)
            val confirm = compose.onNodeWithText(strings.deleteConfirm)
            val cancel = compose.onNodeWithText(strings.cancel)
            fun openConfirmation() {
                title.assertIsDisplayed().performTouchInput { longClick() }
                trash.assertIsDisplayed().performTouchInput { click() }
                compose.mainClock.advanceTimeBy(1_000)
                confirm.assertIsDisplayed()
                assertNotNull(local.getAnimeById(anime.id))
            }

            openConfirmation()
            cancel.performTouchInput { click() }
            compose.waitUntil(5_000) { runCatching { trash.fetchSemanticsNode() }.isFailure }
            assertNotNull(local.getAnimeById(anime.id))

            openConfirmation()
            confirm.performClick()
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitUntil("the confirmation sheet closes", 5_000) {
                runCatching { confirm.fetchSemanticsNode() }.isFailure
            }
            compose.waitUntil("the confirmed title is removed from storage", 10_000) {
                local.getAnimeById(anime.id) == null
            }
            compose.waitUntil(10_000) { runCatching { title.fetchSemanticsNode() }.isFailure }
        } finally {
            if (local.getAnimeById(anime.id) != null) local.deleteAnime(anime.id)
            withContext(Dispatchers.Main) { models.clear() }
        }
    }
}
