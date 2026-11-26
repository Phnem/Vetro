package com.example.myapplication.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.home.cardmenu.ActionMenuGesture
import com.example.myapplication.ui.home.cardmenu.ActionMenuItem
import com.example.myapplication.ui.home.cardmenu.ActionMenuOverlay
import com.example.myapplication.ui.home.cardmenu.collectionGestures
import com.example.myapplication.ui.shared.PlacedCoordinates
import com.example.myapplication.ui.shared.trackPlacement
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CollectionActionGesturesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionCanBeReleasedFromTheOriginalPressOrTappedAfterwards() {
        var actions = 0
        var hoverTicks = 0
        compose.setContent {
            val gesture = remember { ActionMenuGesture { hoverTicks++ } }
            val card = remember { PlacedCoordinates() }
            val backdrop = rememberLayerBackdrop {
                drawRect(Color.White)
                drawContent()
            }
            var open by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().testTag("gestureRoot").collectionGestures(gesture)) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = 100.dp).size(200.dp, 64.dp)
                            .trackPlacement(card).background(Color.Gray).testTag("card")
                            .combinedClickable(
                                onLongClick = {
                                    gesture.arm()
                                    open = true
                                },
                                onClick = {},
                            )
                    )
                }
                if (open) ActionMenuOverlay(
                    boundsInRoot = checkNotNull(card.boundsInRoot()),
                    visible = true,
                    backdrop = backdrop,
                    bottomInset = 0.dp,
                    topInset = 0.dp,
                    actions = listOf(ActionMenuItem(Icons.Rounded.Delete, Color.Red, "Test action") {
                        actions++
                        open = false
                    }),
                    onDismiss = { open = false },
                    onClosed = { open = false },
                    gesture = gesture,
                ) { Box(Modifier.fillMaxSize().background(Color.Gray)) }
            }
        }

        val root = compose.onNodeWithTag("gestureRoot")
        val card = compose.onNodeWithTag("card")
        val action = compose.onNodeWithContentDescription("Test action")
        fun startHold() {
            val start = card.fetchSemanticsNode().boundsInRoot.center - root.fetchSemanticsNode().boundsInRoot.topLeft
            root.performTouchInput { down(start) }
            compose.mainClock.advanceTimeBy(850)
            compose.waitUntil(5_000) { runCatching { action.fetchSemanticsNode() }.isSuccess }
        }

        startHold()
        val target = action.fetchSemanticsNode().boundsInRoot.center - root.fetchSemanticsNode().boundsInRoot.topLeft
        root.performTouchInput {
            moveTo(target)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, hoverTicks)
            assertEquals(1, actions)
        }

        startHold()
        root.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, actions) }
        action.performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, actions) }
    }

    @Test fun aCollectionSwipeStillMovesTheHorizontalPager() {
        compose.setContent {
            val pager = rememberPagerState(pageCount = { 2 })
            val gesture = remember { ActionMenuGesture {} }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                if (page == 0) {
                    Box(
                        Modifier.fillMaxSize().testTag("collection").collectionGestures(gesture),
                        contentAlignment = Alignment.Center,
                    ) { Text("Collection") }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Next page") }
                }
            }
        }
        compose.onNodeWithTag("collection").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Next page").assertIsDisplayed()
    }
}
