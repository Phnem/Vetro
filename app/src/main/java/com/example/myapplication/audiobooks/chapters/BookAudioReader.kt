package com.example.myapplication.audiobooks.chapters

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.myapplication.media.subtitles.whisper.LinearResampler
import com.example.myapplication.media.subtitles.whisper.WHISPER_SAMPLE_RATE
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Звук книги без плеера: MediaExtractor + MediaCodec поверх тех же источников данных, что у
 * плеера (vetro-audio → свежая ссылка сайта, content:// папки, торрент), поэтому работает быстрее
 * реального времени и для любого трека.
 */
@UnstableApi
class BookAudioReader(private val dataSources: DataSource.Factory) {

    /**
     * Огибающая громкости трека: один байт на [SilenceDetector.FRAME_MS] (дБ + 100, 0..100).
     * Начинает с кадра [fromFrame] (продолжение прерванного разбора), кадры отдаёт пачками в [sink].
     * [onProgress] — доля трека 0..1; [checkpoint] вызывается между пачками (пауза на перегрев).
     */
    suspend fun envelope(
        uri: Uri,
        fromFrame: Int,
        sink: (ByteArray) -> Unit,
        onProgress: (Float) -> Unit,
        checkpoint: suspend () -> Unit,
    ) = decode(uri, fromFrame * SilenceDetector.FRAME_MS) { format, durationUs ->
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val samplesPerFrame = (rate * SilenceDetector.FRAME_MS / 1000).toInt().coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        val pending = java.io.ByteArrayOutputStream(1024)
        var frames = fromFrame.toLong()
        val startUs = fromFrame * SilenceDetector.FRAME_MS * 1000
        object : PcmSink {
            override suspend fun mono(samples: FloatArray, size: Int, ptsUs: Long) {
                // Продолжение с середины: декодер начинает с точки синхронизации до цели — её не считаем дважды.
                val skip = if (ptsUs < startUs) ((startUs - ptsUs) * rate / 1_000_000).toInt().coerceAtMost(size) else 0
                for (i in skip until size) {
                    val s = samples[i]
                    sum += s * s
                    if (++count == samplesPerFrame) {
                        val rms = kotlin.math.sqrt(sum / count)
                        val db = if (rms <= 1e-5) -100.0 else 20 * log10(rms)
                        pending.write((db + 100).roundToInt().coerceIn(0, 100))
                        sum = 0.0
                        count = 0
                        frames++
                    }
                }
                if (pending.size() >= FLUSH_FRAMES) {
                    sink(pending.toByteArray())
                    pending.reset()
                    if (durationUs > 0) onProgress((frames * SilenceDetector.FRAME_MS * 1000f / durationUs).coerceIn(0f, 1f))
                    checkpoint()
                }
            }

            override suspend fun finish() {
                if (pending.size() > 0) sink(pending.toByteArray())
                onProgress(1f)
            }
        }
    }

    /** 16 кГц моно для Whisper: [lengthMs] звука трека с [fromMs]. */
    suspend fun speech(uri: Uri, fromMs: Long, lengthMs: Long): FloatArray {
        val out = FloatArray((WHISPER_SAMPLE_RATE * lengthMs / 1000).toInt())
        var size = 0
        val startUs = fromMs * 1000
        decode(uri, fromMs) { format, _ ->
            val resampler = LinearResampler().apply { reset(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), WHISPER_SAMPLE_RATE) }
            object : PcmSink {
                override suspend fun mono(samples: FloatArray, size0: Int, ptsUs: Long) {
                    // После перехода декодер начинает с ближайшей точки синхронизации — лишнее до цели пропускаем.
                    val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val skip = if (ptsUs < startUs) ((startUs - ptsUs) * rate / 1_000_000).toInt().coerceAtMost(size0) else 0
                    if (skip >= size0) return
                    resampler.process(samples.copyOfRange(skip, size0)) { if (size < out.size) out[size++] = it }
                    if (size >= out.size) throw Done
                }

                override suspend fun finish() = Unit
            }
        }
        return out.copyOf(size)
    }

    private interface PcmSink {
        suspend fun mono(samples: FloatArray, size: Int, ptsUs: Long)
        suspend fun finish()
    }

    private object Done : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Декодирует звуковую дорожку [uri] с [fromMs], отдавая моно float (−1..1) приёмнику. */
    private suspend fun decode(uri: Uri, fromMs: Long, makeSink: (MediaFormat, Long) -> PcmSink) {
        val extractor = MediaExtractor()
        val media = Media3MediaDataSource(dataSources, uri)
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(media)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            if (fromMs > 0) extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var outFormat = decoder.outputFormat
            var sink: PcmSink? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var mono = FloatArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = decoder.outputFormat
                    outIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        val isFloat = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        val bytesPerSample = if (isFloat) 4 else 2
                        val frames = info.size / (bytesPerSample * channels)
                        if (mono.size < frames) mono = FloatArray(frames)
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += if (isFloat) buffer.float else buffer.short / 32768f
                            mono[f] = s / channels
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        val target = sink ?: makeSink(
                            MediaFormat().apply { setInteger(MediaFormat.KEY_SAMPLE_RATE, outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)) },
                            durationUs,
                        ).also { sink = it }
                        if (frames > 0) target.mono(mono, frames, info.presentationTimeUs)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            target.finish()
                            return
                        }
                    }
                }
            }
        } catch (_: Done) {
            // Нужный кусок набран.
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { media.close() }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        /** Пачка огибающей: 600 кадров — минута звука. */
        const val FLUSH_FRAMES = 600
    }
}

/**
 * Произвольный доступ MediaExtractor поверх потокового DataSource Media3: чтение подряд идёт из
 * открытого соединения, небольшой прыжок вперёд — пропуском, остальное — новым открытием с позиции.
 */
@UnstableApi
private class Media3MediaDataSource(private val factory: DataSource.Factory, private val uri: Uri) : MediaDataSource() {
    private var source: DataSource? = null
    private var position = -1L
    private var size = C.LENGTH_UNSET.toLong()
    private var sizeKnown = false
    private val skipBuffer = ByteArray(64 * 1024)

    @Synchronized
    override fun readAt(pos: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (sizeKnown && size >= 0 && pos >= size) return -1
        if (source == null || pos != position) {
            if (source != null && pos > position && pos - position <= SKIP_LIMIT) {
                var left = pos - position
                while (left > 0) {
                    val n = source!!.read(skipBuffer, 0, minOf(left, skipBuffer.size.toLong()).toInt())
                    if (n == C.RESULT_END_OF_INPUT) return -1
                    left -= n
                    position += n
                }
            } else {
                open(pos)
            }
        }
        val n = source!!.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) return -1
        position += n
        return n
    }

    @Synchronized
    override fun getSize(): Long {
        if (!sizeKnown) open(0)
        return size
    }

    private fun open(pos: Long) {
        runCatching { source?.close() }
        val ds = factory.createDataSource()
        val length = ds.open(DataSpec.Builder().setUri(uri).setPosition(pos).build())
        if (!sizeKnown) {
            size = if (length == C.LENGTH_UNSET.toLong()) -1L else pos + length
            sizeKnown = true
        }
        source = ds
        position = pos
    }

    @Synchronized
    override fun close() {
        runCatching { source?.close() }
        source = null
    }

    private companion object {
        const val SKIP_LIMIT = 512 * 1024L
    }
}
