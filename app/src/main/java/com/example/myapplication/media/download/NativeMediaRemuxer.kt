package com.example.myapplication.media.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Remuxes an Android-supported transport stream into a seekable MP4 without transcoding.
 *
 * Every supported video and audio track is copied. This is important for local seeking and also
 * preserves genuine multi-audio files supplied by one synchronized source.
 */
object NativeMediaRemuxer {

    fun remuxToMp4(
        source: File,
        destination: File,
        isCancelled: () -> Boolean = { false },
    ): File {
        val extractor = MediaExtractor()
        val temp = File(destination.parentFile, destination.nameWithoutExtension + ".remux.mp4")
        runCatching { temp.delete() }

        try {
            extractor.setDataSource(source.absolutePath)
            val selectedTracks = buildList {
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        add(index to format)
                    }
                }
            }
            if (selectedTracks.none { it.second.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }) {
                throw IOException("Transport stream has no supported video track")
            }

            // AAC из TS приходит в ADTS: MP4 ждёт голые кадры и csd-0 (см. AdtsFrames).
            val aacSampleRates = HashMap<Int, Int>()
            val aacTimelines = HashMap<Int, AdtsFrames.Timeline>()
            for ((inputIndex, format) in selectedTracks) {
                if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) continue
                val sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: continue
                aacSampleRates[inputIndex] = sampleRate
                if (format.containsKey(MediaFormat.KEY_IS_ADTS)) format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                if (!format.containsKey(CSD_0)) {
                    val channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
                    val objectType = format.getIntegerOrNull(MediaFormat.KEY_AAC_PROFILE) ?: AAC_LC
                    AdtsFrames.audioSpecificConfig(sampleRate, channels, objectType)
                        ?.let { format.setByteBuffer(CSD_0, ByteBuffer.wrap(it)) }
                }
            }

            val muxer = MediaMuxer(
                temp.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            try {
                val outputTracks = selectedTracks.associate { (inputIndex, format) ->
                    extractor.selectTrack(inputIndex)
                    inputIndex to muxer.addTrack(format)
                }
                val maxInputSize = selectedTracks
                    .mapNotNull { (_, format) ->
                        format.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                            ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    }
                    .maxOrNull()
                    ?.coerceAtLeast(DEFAULT_BUFFER_BYTES)
                    ?: DEFAULT_BUFFER_BYTES
                val buffer = ByteBuffer.allocateDirect(maxInputSize)
                val info = MediaCodec.BufferInfo()

                muxer.start()
                while (true) {
                    if (isCancelled()) throw RemuxCancelledException()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val inputTrack = extractor.sampleTrackIndex
                    val outputTrack = outputTracks[inputTrack]
                    val sampleTime = extractor.sampleTime.coerceAtLeast(0L)
                    val aacSampleRate = aacSampleRates[inputTrack]
                    val adts = if (aacSampleRate != null) AdtsFrames.split(buffer, 0, size) else emptyList()
                    if (outputTrack != null && adts.isNotEmpty()) {
                        // Один сэмпл MP4 = один кадр AAC (1024 отсчёта); время — непрерывный счёт
                        // кадров (см. AdtsFrames.Timeline), частота — из заголовка кадра.
                        val rate = adts.first().sampleRate.takeIf { it > 0 } ?: aacSampleRate!!
                        val frameUs = AAC_FRAME_SAMPLES * 1_000_000L / rate
                        val start = aacTimelines.getOrPut(inputTrack) { AdtsFrames.Timeline() }
                            .place(sampleTime, adts.size, frameUs)
                        adts.forEachIndexed { index, frame ->
                            info.set(
                                frame.payloadOffset,
                                frame.payloadSize,
                                start + index * frameUs,
                                extractor.sampleFlags,
                            )
                            muxer.writeSampleData(outputTrack, buffer, info)
                        }
                    } else if (outputTrack != null) {
                        info.set(0, size, sampleTime, extractor.sampleFlags)
                        muxer.writeSampleData(outputTrack, buffer, info)
                    }
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }

            if (!MediaFileValidator.isPlayableVideo(temp)) {
                throw IOException("Native remux did not produce a valid MP4")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Cannot replace previous episode file")
            }
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                if (!temp.delete()) throw IOException("Cannot finalize remuxed episode")
            }
            return destination
        } catch (error: Exception) {
            runCatching { temp.delete() }
            throw error
        } finally {
            runCatching { extractor.release() }
        }
    }

    class RemuxCancelledException : IOException("Native remux cancelled")

    private const val DEFAULT_BUFFER_BYTES = 4 * 1024 * 1024
    private const val CSD_0 = "csd-0"
    private const val AAC_LC = 2
    private const val AAC_FRAME_SAMPLES = 1024L

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
