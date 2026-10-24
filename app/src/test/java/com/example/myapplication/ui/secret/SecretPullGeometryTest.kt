package com.example.myapplication.ui.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretPullGeometryTest {
    private val width = 1080f

    @Test
    fun `150 px of finger travel stretches the edge by about 40 px`() {
        val geometry = SecretPullGeometry(width, startX = 0f)
        assertEquals(40f, geometry.stretchFor(150f), 6f)
    }

    @Test
    fun `stretch is monotonic and never exceeds the cap`() {
        val geometry = SecretPullGeometry(width, startX = 0f)
        var previous = 0f
        for (travel in 0..2000 step 25) {
            val stretch = geometry.stretchFor(travel.toFloat())
            assertTrue(stretch >= previous)
            assertTrue(stretch <= geometry.maxStretch)
            previous = stretch
        }
    }

    @Test
    fun `barrier breaks at 85 percent of the available travel`() {
        val geometry = SecretPullGeometry(width, startX = 540f)
        assertEquals(540f * 0.85f, geometry.breakTravel, 0.01f)
        assertFalse(geometry.isBroken(geometry.breakTravel - 1f))
        assertTrue(geometry.isBroken(geometry.breakTravel))
    }

    @Test
    fun `shorter travel makes the same finger distance stretch more`() {
        val full = SecretPullGeometry(width, startX = 0f)
        val half = SecretPullGeometry(width, startX = 540f)
        assertTrue(half.stretchFor(150f) > full.stretchFor(150f))
    }

    @Test
    fun `start too close to the right edge disables the gesture`() {
        assertTrue(SecretPullGeometry(width, startX = 640f).isEnabled)
        assertFalse(SecretPullGeometry(width, startX = 700f).isEnabled)
        assertEquals(0f, SecretPullGeometry(width, startX = 900f).stretchFor(300f), 0f)
    }

    @Test
    fun `bulge flattens into a straight edge when fully open`() {
        val open = SecretBulge.screenX(y = 100f, touchY = 900f, height = 2000f, edge = width, width = width)
        assertEquals(width, open, 0.01f)
        val pulled = SecretBulge.screenX(y = 900f, touchY = 900f, height = 2000f, edge = 200f, width = width)
        val far = SecretBulge.screenX(y = 100f, touchY = 900f, height = 2000f, edge = 200f, width = width)
        assertEquals(200f, pulled, 0.01f)
        assertTrue(far < pulled)
    }
}
