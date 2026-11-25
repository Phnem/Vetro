package com.example.myapplication.ui.shared.loading

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

data class BubbleTransform(
    val x: Float,
    val y: Float,
    val scaleX: Float,
    val scaleY: Float,
)

data class BubbleClusterFrame(
    val bubbles: List<BubbleTransform>,
    val satelliteX: Float,
    val satelliteY: Float,
    val satelliteAlpha: Float,
)

/**
 * Motion extracted from the supplied reference: five touching bubbles form a 3×2 cluster while
 * a smaller bubble alternately crosses it from both sides. Each contact travels through the
 * cluster as a short compression wave with a damped spring return.
 */
object BubbleClusterMotion {

    const val CYCLE_MILLIS = 1900

    private const val SATELLITE_EDGE_X = 2.62f
    private const val SATELLITE_Y = 0.50f
    private const val CONTACT_RISE = 24f
    private const val SPRING_DAMPING = 9.5f
    private const val SPRING_FREQUENCY = 25f

    private val bubbleCenters = listOf(
        Point(-1.25f, -0.62f),
        Point(0f, -0.62f),
        Point(1.25f, -0.62f),
        Point(-0.63f, 0.58f),
        Point(0.63f, 0.58f),
    )

    private val contactsLeft by lazy { contactPhases(direction = -1f) }
    private val contactsRight by lazy { contactPhases(direction = 1f) }

    private fun contactPhases(direction: Float): FloatArray {
        val startX = -direction * SATELLITE_EDGE_X
        val endX = direction * SATELLITE_EDGE_X
        return FloatArray(bubbleCenters.size) { i ->
            inverseSmootherStep(((bubbleCenters[i].x - startX) / (endX - startX)).coerceIn(0f, 1f))
        }
    }

    fun frame(phase: Float): BubbleClusterFrame {
        val t = wrap(phase)
        val travellingLeft = t < 0.5f
        val leg = if (travellingLeft) t * 2f else (t - 0.5f) * 2f
        val direction = if (travellingLeft) -1f else 1f
        val startX = -direction * SATELLITE_EDGE_X
        val endX = direction * SATELLITE_EDGE_X
        val travel = smootherStep(leg)
        val satelliteX = lerp(startX, endX, travel)

        val contacts = if (travellingLeft) contactsLeft else contactsRight
        val bubbles = bubbleCenters.mapIndexed { index, center ->
            val contactPhase = contacts[index]
            val response = springResponse(leg - contactPhase)
            val directness = 1f - 0.12f * kotlin.math.abs(center.y - SATELLITE_Y)
            val compression = response * directness.coerceIn(0.78f, 1f)

            BubbleTransform(
                x = center.x + direction * 0.15f * compression,
                y = center.y - 0.035f * compression,
                scaleX = (1f - 0.22f * compression).coerceIn(0.76f, 1.08f),
                scaleY = (1f + 0.14f * compression).coerceIn(0.90f, 1.16f),
            )
        }

        return BubbleClusterFrame(
            bubbles = bubbles,
            satelliteX = satelliteX,
            satelliteY = SATELLITE_Y + 0.055f * sin(PI.toFloat() * leg),
            satelliteAlpha = satelliteVisibility(satelliteX),
        )
    }

    private fun springResponse(timeFromContact: Float): Float {
        if (timeFromContact < 0f) {
            return 0.58f * exp(CONTACT_RISE * timeFromContact)
        }
        return exp(-SPRING_DAMPING * timeFromContact) *
            cos(SPRING_FREQUENCY * timeFromContact)
    }

    private fun satelliteVisibility(x: Float): Float {
        val distance = kotlin.math.abs(x)
        return smootherStep(((distance - 1.10f) / 0.58f).coerceIn(0f, 1f))
    }

    private fun inverseSmootherStep(value: Float): Float {
        var low = 0f
        var high = 1f
        repeat(10) {
            val middle = (low + high) / 2f
            if (smootherStep(middle) < value) low = middle else high = middle
        }
        return (low + high) / 2f
    }

    private fun smootherStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    private fun wrap(value: Float): Float {
        val remainder = value % 1f
        return if (remainder < 0f) remainder + 1f else remainder
    }

    private fun lerp(from: Float, to: Float, progress: Float): Float =
        from + (to - from) * progress

    private data class Point(
        val x: Float,
        val y: Float,
    )
}
