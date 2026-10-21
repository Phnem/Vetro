package com.example.myapplication.media.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaFileValidatorTest {

    @Test
    fun `rejects 1x1 PNG saved with mp4 extension`() {
        val file = File.createTempFile("placeholder", ".mp4")
        try {
            file.writeBytes(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                )
            )

            assertFalse(MediaFileValidator.isPlayableMp4(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `accepts plausible MP4 with ftyp signature`() {
        val file = File.createTempFile("episode", ".mp4")
        try {
            val bytes = ByteArray(128 * 1024)
            bytes[3] = 0x18
            bytes[4] = 'f'.code.toByte()
            bytes[5] = 't'.code.toByte()
            bytes[6] = 'y'.code.toByte()
            bytes[7] = 'p'.code.toByte()
            file.writeBytes(bytes)

            assertTrue(MediaFileValidator.isPlayableMp4(file))
        } finally {
            file.delete()
        }
    }
    @Test
    fun `accepts concatenated MPEG TS stream`() {
        val file = File.createTempFile("episode-hls", ".mp4")
        try {
            val bytes = ByteArray(128 * 1024)
            bytes[0] = 0x47
            bytes[188] = 0x47
            bytes[376] = 0x47
            file.writeBytes(bytes)

            assertTrue(MediaFileValidator.isPlayableVideo(file))
            assertTrue(MediaFileValidator.isPlayableMp4(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `accepts EBML Matroska MKV stream`() {
        val file = File.createTempFile("episode-mkv", ".mp4")
        try {
            val bytes = ByteArray(128 * 1024)
            bytes[0] = 0x1A.toByte()
            bytes[1] = 0x45.toByte()
            bytes[2] = 0xDF.toByte()
            bytes[3] = 0xA3.toByte()
            file.writeBytes(bytes)

            assertTrue(MediaFileValidator.isPlayableVideo(file))
            assertTrue(MediaFileValidator.isPlayableMp4(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `accepts RIFF AVI stream`() {
        val file = File.createTempFile("episode-avi", ".mp4")
        try {
            val bytes = ByteArray(128 * 1024)
            bytes[0] = 'R'.code.toByte()
            bytes[1] = 'I'.code.toByte()
            bytes[2] = 'F'.code.toByte()
            bytes[3] = 'F'.code.toByte()
            bytes[8] = 'A'.code.toByte()
            bytes[9] = 'V'.code.toByte()
            bytes[10] = 'I'.code.toByte()
            bytes[11] = ' '.code.toByte()
            file.writeBytes(bytes)

            assertTrue(MediaFileValidator.isPlayableVideo(file))
            assertTrue(MediaFileValidator.isPlayableMp4(file))
        } finally {
            file.delete()
        }
    }
}
