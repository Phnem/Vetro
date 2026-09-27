package com.example.myapplication.media.subtitles.whisper

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureTest {

    private fun sine(rate: Int, seconds: Double, hz: Double) =
        FloatArray((rate * seconds).toInt()) { i -> sin(2 * PI * hz * i / rate).toFloat() * 0.5f }

    @Test
    fun `48 kHz becomes 16 kHz without seams between buffers`() {
        val input = sine(48_000, 1.0, 440.0)
        val out = ArrayList<Float>()
        val r = LinearResampler().apply { reset(48_000, 16_000) }
        // Буферы неровной длины — стыки не должны давать повторов и провалов.
        var offset = 0
        for (size in generateSequence(1_000) { if (it == 1_000) 777 else 1_000 }) {
            if (offset >= input.size) break
            val end = minOf(input.size, offset + size)
            r.process(input.copyOfRange(offset, end)) { out += it }
            offset = end
        }
        assertEquals(16_000.0, out.size.toDouble(), 2.0)
        val expected = sine(16_000, 1.0, 440.0)
        val maxError = out.indices.take(15_990).maxOf { abs(out[it] - expected[it]) }
        assertTrue("синус сохраняет форму: $maxError", maxError < 0.02f)
    }

    @Test
    fun `stereo 44_1 kHz is mixed to mono, audio passes through unchanged`() {
        val p = PcmCaptureProcessor()
        p.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val frames = 4_410
        val bytes = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { bytes.putShort(16_384); bytes.putShort(0) } // левый 0.5, правый 0 → моно 0.25
        bytes.flip()
        p.queueInput(bytes)
        val passed = p.output
        assertEquals("звук проходит как был", frames * 4, passed.remaining())
        val samples = p.samples()
        assertEquals(1_600.0, samples.size.toDouble(), 2.0)
        assertTrue(samples.all { abs(it - 0.25f) < 1e-3 })
    }
}
