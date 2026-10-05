package com.example.myapplication.manga.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadTest {

    @Test
    fun speed_is_zero_until_a_full_window_has_passed() {
        var now = 0L
        val meter = SpeedMeter(windowMillis = 600, clock = { now })
        assertEquals(0L, meter.update(0))
        now = 300
        assertEquals(0L, meter.update(500_000))
    }

    @Test
    fun speed_follows_bytes_per_window_and_is_smoothed() {
        var now = 0L
        val meter = SpeedMeter(windowMillis = 500, smoothing = 0.5, clock = { now })
        meter.update(0)
        now = 500
        // 1 МБ за 0,5 с = 2 МБ/с: первое значение берётся как есть.
        assertEquals(2_000_000L, meter.update(1_000_000))
        now = 1000
        // Следующее окно вдвое медленнее (1 МБ/с): среднее между старым и новым.
        assertEquals(1_500_000L, meter.update(1_500_000))
    }

    @Test
    fun megabytes_use_a_decimal_only_when_the_number_is_small() {
        assertEquals("128", DownloadFormat.megabytes(127_740_540L, decimalComma = true))
        assertEquals("2,4", DownloadFormat.megabytes(2_400_000L, decimalComma = true))
        assertEquals("2.4", DownloadFormat.megabytes(2_400_000L, decimalComma = false))
        assertEquals("0.0", DownloadFormat.megabytes(-5L, decimalComma = false))
    }

    @Test
    fun steps_split_the_total_progress_between_detector_and_ocr() {
        val (d0, o0) = TranslationModels.stepFractions(0)
        assertEquals(0f, d0, 0f)
        assertEquals(0f, o0, 0f)

        val (d1, o1) = TranslationModels.stepFractions(TranslationModels.DETECTOR_BYTES / 2)
        assertEquals(0.5f, d1, 0.001f)
        assertEquals(0f, o1, 0f)

        val ocrBytes = TranslationModels.TOTAL_BYTES - TranslationModels.DETECTOR_BYTES
        val (d2, o2) = TranslationModels.stepFractions(TranslationModels.DETECTOR_BYTES + ocrBytes / 2)
        assertEquals(1f, d2, 0f)
        assertEquals(0.5f, o2, 0.001f)

        val (d3, o3) = TranslationModels.stepFractions(TranslationModels.TOTAL_BYTES)
        assertEquals(1f, d3, 0f)
        assertEquals(1f, o3, 0f)
    }

    @Test
    fun downloading_state_reports_fraction_and_eta() {
        val s = ModelsState.Downloading(doneBytes = 40_000_000, totalBytes = 100_000_000, bytesPerSecond = 2_000_000)
        assertEquals(0.4f, s.fraction, 0.0001f)
        assertEquals(30L, s.etaSeconds)
    }

    @Test
    fun eta_is_unknown_until_a_speed_is_measured() {
        val s = ModelsState.Downloading(doneBytes = 1, totalBytes = 100)
        assertNull(s.etaSeconds)
        assertTrue(ModelsState.Downloading(0, 0).fraction == 0f)
    }
}
