package com.example.myapplication.ui.home.cardmenu

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import com.example.myapplication.ui.shared.PlacedCoordinates
import com.example.myapplication.ui.shared.trackPlacement

/** Keeps the original finger stream when the action menu is composed above the pressed row. */
@Stable
internal class ActionMenuGesture(private val onHover: () -> Unit) {
    var pointerInRoot by mutableStateOf<Offset?>(null)
        private set
    var hoveredAction by mutableStateOf<Int?>(null)
        private set

    var actions: List<ActionMenuItem> = emptyList()
    val buttonCoordinates = mutableMapOf<Int, LayoutCoordinates>()

    private var latestPosition: Offset? = null
    private var pressOrigin: Offset? = null

    fun arm() {
        pressOrigin = latestPosition
    }

    fun clear() {
        latestPosition = null
        pressOrigin = null
        pointerInRoot = null
        hoveredAction = null
    }

    fun observe(position: Offset) {
        latestPosition = position
    }

    fun drag(position: Offset, slop: Float): Boolean {
        val origin = pressOrigin ?: return false
        if (pointerInRoot == null && (position - origin).getDistance() <= slop) return true
        pointerInRoot = position
        val hit = actions.indices.firstOrNull { index ->
            buttonCoordinates[index]?.takeIf { it.isAttached }?.boundsInRoot()
                ?.let { (position - it.center).getDistance() <= minOf(it.width, it.height) / 2f } == true
        }
        if (hit != hoveredAction) {
            hoveredAction = hit
            if (hit != null) onHover()
        }
        return true
    }

    fun release() {
        val selected = hoveredAction?.let(actions::getOrNull)
        clear()
        selected?.onClick?.invoke()
    }
}

@Composable
internal fun Modifier.collectionGestures(menu: ActionMenuGesture?): Modifier {
    if (menu == null) return this
    val coordinates = remember(menu) { PlacedCoordinates() }
    return trackPlacement(coordinates).pointerInput(menu) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            try {
                coordinates.coordinates?.takeIf { it.isAttached }
                    ?.let { menu.observe(it.localToRoot(down.position)) }
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                    val root = coordinates.coordinates?.takeIf { it.isAttached } ?: break
                    if (!finger.pressed && finger.isConsumed) break
                    val position = root.localToRoot(finger.position)
                    if (menu.drag(position, viewConfiguration.touchSlop)) {
                        finger.consume()
                        if (!finger.pressed) menu.release()
                    } else if (finger.pressed) {
                        menu.observe(position)
                    }
                } while (finger.pressed)
            } finally {
                menu.clear()
            }
        }
    }
}
