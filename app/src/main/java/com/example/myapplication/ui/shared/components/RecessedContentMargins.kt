package com.example.myapplication.ui.shared.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/**
 * Continue the actual page's border pixels into the margins of a recessed screen. Recording and
 * drawing stay on the GPU: no screenshot readback, bitmap allocation or second composition of the
 * page. Different artwork edges and scrolled backgrounds keep their own colors in the same frame.
 */
internal fun DrawScope.drawRecessedContentMargins(
    content: GraphicsLayer,
    scale: Float,
    bottomRadius: Float,
) {
    if (scale >= 1f || scale <= 0f || size.width < 1f || size.height < 1f) return
    val w = size.width
    val h = size.height
    val inset = w * (1f - scale) / 2f
    val bottom = h * scale
    val radius = bottomRadius.coerceIn(0f, minOf(w * scale / 2f, bottom / 2f))
    // Native layer clipping and the inverse canvas clip both antialias the outline. Overlap one
    // physical pixel over the already-drawn page so their partial coverage cannot reveal a seam.
    val overlap = minOf(1f, w * scale / 4f, bottom / 4f)
    val fillBottom = bottom - overlap
    val innerRadius = (radius - overlap).coerceAtLeast(0f)
    val page = Path().apply {
        addRoundRect(
            RoundRect(
                left = inset + overlap, top = 0f, right = w - inset - overlap, bottom = fillBottom,
                bottomLeftCornerRadius = CornerRadius(innerRadius),
                bottomRightCornerRadius = CornerRadius(innerRadius),
            )
        )
    }
    // The page has already drawn and recorded its current content. Paint only outside its outline,
    // before the scaffold adds its common scrim and sheet, preserving their existing animation.
    clipPath(page, ClipOp.Difference) {
        drawLayerSlice(content, Rect(0f, 0f, 1f, h), Rect(0f, 0f, inset + radius + overlap, bottom))
        drawLayerSlice(content, Rect(w - 1f, 0f, w, h), Rect(w - inset - radius - overlap, 0f, w, bottom))
        drawLayerSlice(content, Rect(0f, h - 1f, w, h), Rect(inset, fillBottom, w - inset, h))
        drawLayerSlice(content, Rect(0f, h - 1f, 1f, h), Rect(0f, fillBottom, inset, h))
        drawLayerSlice(content, Rect(w - 1f, h - 1f, w, h), Rect(w - inset, fillBottom, w, h))
    }
}

private fun DrawScope.drawLayerSlice(content: GraphicsLayer, source: Rect, destination: Rect) {
    if (destination.width <= 0f || destination.height <= 0f) return
    clipRect(destination.left, destination.top, destination.right, destination.bottom) {
        withTransform({
            translate(destination.left, destination.top)
            scale(destination.width / source.width, destination.height / source.height, Offset.Zero)
            translate(-source.left, -source.top)
        }) {
            drawLayer(content)
        }
    }
}
