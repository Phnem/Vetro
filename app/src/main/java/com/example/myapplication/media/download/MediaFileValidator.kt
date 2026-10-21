package com.example.myapplication.media.download

import java.io.File
import java.io.FileInputStream

object MediaFileValidator {
    private const val MIN_VIDEO_BYTES = 128 * 1024L
    private const val MPEG_TS_PACKET_BYTES = 188

    fun isPlayableVideo(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_VIDEO_BYTES) return false
        val header = ByteArray(MPEG_TS_PACKET_BYTES * 2)
        val count = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(0)

        val isIsoBmff = count >= 12 &&
            header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
        val isMpegTs = count > MPEG_TS_PACKET_BYTES &&
            header[0] == MPEG_TS_SYNC_BYTE &&
            header[MPEG_TS_PACKET_BYTES] == MPEG_TS_SYNC_BYTE
        val isEbmlMatroska = count >= 4 &&
            header[0] == 0x1A.toByte() &&
            header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() &&
            header[3] == 0xA3.toByte()
        val isRiffAvi = count >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'A'.code.toByte() &&
            header[9] == 'V'.code.toByte() &&
            header[10] == 'I'.code.toByte() &&
            header[11] == ' '.code.toByte()
        return isIsoBmff || isMpegTs || isEbmlMatroska || isRiffAvi
    }

    /** Kept for source compatibility with the episode menu. */
    fun isPlayableMp4(file: File): Boolean = isPlayableVideo(file)

    private val MPEG_TS_SYNC_BYTE = 0x47.toByte()
}
