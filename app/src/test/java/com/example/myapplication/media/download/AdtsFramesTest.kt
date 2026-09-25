package com.example.myapplication.media.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AdtsFramesTest {

    /** Кадр ADTS: 7-байтный заголовок без CRC (AAC LC, 48 кГц, стерео) + полезная нагрузка. */
    private fun adtsFrame(payload: ByteArray): ByteArray {
        val length = payload.size + 7
        val header = byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, protection absent
            0x4C.toByte(), // profile LC, freq index 3 (48 kHz), channel config high bit 0
            (0x80 or ((length ushr 11) and 0x03)).toByte(), // channels = 2
            ((length ushr 3) and 0xFF).toByte(),
            (((length and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return header + payload
    }

    @Test
    fun `a PES with several ADTS frames splits into bare AAC payloads`() {
        val payloads = listOf(ByteArray(10) { 1 }, ByteArray(300) { 2 }, ByteArray(5) { 3 })
        val bytes = payloads.fold(ByteArray(0)) { acc, p -> acc + adtsFrame(p) }
        val buffer = ByteBuffer.wrap(bytes)

        val frames = AdtsFrames.split(buffer, 0, bytes.size)

        assertEquals(3, frames.size)
        assertEquals(listOf(10, 300, 5), frames.map { it.payloadSize })
        assertEquals(7, frames[0].payloadOffset)
        val second = bytes.copyOfRange(frames[1].payloadOffset, frames[1].payloadOffset + frames[1].payloadSize)
        assertArrayEquals(payloads[1], second)
    }

    @Test
    fun `raw AAC and truncated streams are left alone`() {
        val raw = ByteBuffer.wrap(byteArrayOf(0x21, 0x10, 0x05, 0x40, 0x00, 0x00, 0x00, 0x00))
        assertTrue(AdtsFrames.split(raw, 0, 8).isEmpty())

        val frame = adtsFrame(ByteArray(40))
        val truncated = ByteBuffer.wrap(frame.copyOf(frame.size - 5))
        assertTrue(AdtsFrames.split(truncated, 0, frame.size - 5).isEmpty())
    }

    @Test
    fun `frames carry the header sample rate`() {
        val bytes = adtsFrame(ByteArray(4))
        assertEquals(48_000, AdtsFrames.split(ByteBuffer.wrap(bytes), 0, bytes.size).single().sampleRate)
    }

    @Test
    fun `timeline counts frames continuously and never goes back`() {
        val t = AdtsFrames.Timeline()
        val frame = 21_333L
        // PES с 5 кадрами помечен 0 мс, следующий — 22 мс: второй идёт после пятого кадра, а не с 22 мс.
        assertEquals(0L, t.place(0L, 5, frame))
        assertEquals(5 * frame, t.place(22_000L, 6, frame))
        assertEquals(11 * frame, t.place(150_000L, 10, frame))
        // Дыра в потоке больше полусекунды — новая точка отсчёта.
        assertEquals(5_000_000L, t.place(5_000_000L, 1, frame))
    }

    @Test
    fun `audio specific config for LC 48 kHz stereo`() {
        // 00010 0011 0010 000 -> 0x11 0x90
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AdtsFrames.audioSpecificConfig(48_000, 2))
        assertArrayEquals(byteArrayOf(0x12, 0x10), AdtsFrames.audioSpecificConfig(44_100, 2))
        assertNull(AdtsFrames.audioSpecificConfig(12_345, 2))
    }
}
