package com.example.myapplication.ui.home.cardmenu

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import com.example.myapplication.ui.shared.PlacedCoordinates
import com.example.myapplication.ui.shared.trackPlacement
import androidx.compose.ui.util.lerp
import com.example.myapplication.ui.shared.components.drawLayerSlice
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun Modifier.genieCollapse(
    progress: Animatable<Float, *>,
    destination: () -> LayoutCoordinates?,
): Modifier {
    val cardLayer = rememberGraphicsLayer()
    val coordinates = remember { PlacedCoordinates() }
    return trackPlacement(coordinates).drawWithContent {
        val amount = progress.value
        if (amount <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val source = coordinates.coordinates
        val button = destination()
        if (amount >= 1f || source?.isAttached != true || button?.isAttached != true) {
            return@drawWithContent
        }
        val target = source.localPositionOf(
            button,
            Offset(button.size.width / 2f, button.size.height / 2f),
        )
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@drawWithContent

        cardLayer.record { this@drawWithContent.drawContent() }
        val strips = 32
        val neck = sin(amount * PI).toFloat()
        for (index in 0 until strips) {
            val from = index.toFloat() / strips
            val to = (index + 1f) / strips
            val middle = (from + to) / 2f
            // Lower edges narrow first. Each strip converges on the trash button.
            val squeeze = (amount + middle * neck * 0.46f).coerceAtMost(1f)
            val halfWidth = lerp(width / 2f, 1f, squeeze)
            val center = lerp(width / 2f, target.x, squeeze)
            val startY = lerp(from * height, target.y + (from - 0.5f) * 2f, amount)
            val endY = lerp(to * height, target.y + (to - 0.5f) * 2f, amount)
            drawLayerSlice(
                cardLayer,
                Rect(0f, from * height, width, to * height),
                Rect(center - halfWidth, startY, center + halfWidth, endY),
            )
        }
    }
}
