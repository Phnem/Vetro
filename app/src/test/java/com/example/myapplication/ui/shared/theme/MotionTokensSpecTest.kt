package com.example.myapplication.ui.shared.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Жёсткости нормативной таблицы спеки: `k = (2π / T)²` при массе 1. */
class MotionTokensSpecTest {

    @Test
    fun stiffness_matches_spec_periods() {
        assertEquals(6168.5f, MotionTokens.stiffnessForPeriod(80), 1f)
        assertEquals(986.96f, MotionTokens.stiffnessForPeriod(200), 0.5f)
        assertEquals(685.4f, MotionTokens.stiffnessForPeriod(240), 0.5f)
        assertEquals(385.5f, MotionTokens.stiffnessForPeriod(320), 0.5f)
        assertEquals(1542.1f, MotionTokens.stiffnessForPeriod(160), 0.5f)
    }
}
