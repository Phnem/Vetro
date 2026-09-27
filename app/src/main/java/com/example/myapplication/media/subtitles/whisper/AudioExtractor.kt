package com.example.myapplication.media.subtitles.whisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Частота, которую ждёт Whisper. */
const val WHISPER_SAMPLE_RATE = 16_000

/**
 * Звук диапазона текущего видео как 16 кГц моно float — вход Whisper. Media3 Transformer читает
 * источник так же, как плеер (тот же [MediaSource.Factory] с заголовками источника, HLS/DASH/файл),
 * и не привязан к часам воспроизведения — работает быстрее реального времени. Сжатый звук, который
 * Transformer обязан записать, — побочный файл, он удаляется.
 */
@OptIn(UnstableApi::class)
class AudioExtractor(
    private val context: Context,
    private val scratchDir: File,
) {
    /**
     * [mediaSourceFactory] — из сессии плеера; [startMs]/[endMs] — диапазон видео (end = C.TIME_END_OF_SOURCE
     * до конца). Возвращает сэмплы от начала диапазона.
     */
    suspend fun extract(
        mediaItem: MediaItem,
        mediaSourceFactory: MediaSource.Factory,
        startMs: Long,
        endMs: Long,
    ): FloatArray {
        val capture = PcmCaptureProcessor()
        val clipped = mediaItem.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(clipped)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf<AudioProcessor>(capture), emptyList()))
            .build()
        scratchDir.mkdirs()
        val output = File.createTempFile("whisper-audio-", ".m4a", scratchDir)
        try {
            runOnLooper(edited, mediaSourceFactory, output)
        } finally {
            output.delete()
        }
        return capture.samples()
    }

    private suspend fun runOnLooper(edited: EditedMediaItem, factory: MediaSource.Factory, output: File) =
        suspendCancellableCoroutine { cont ->
            // Transformer живёт на потоке с Looper; главный подходит — тяжёлая работа у него в своих потоках.
            val handler = Handler(Looper.getMainLooper())
            var transformer: Transformer? = null
            handler.post {
                val t = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setAssetLoaderFactory(
                        DefaultAssetLoaderFactory(
                            context,
                            DefaultDecoderFactory.Builder(context).build(),
                            Clock.DEFAULT,
                            factory,
                            DataSourceBitmapLoader(context),
                        ),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer = t
                if (cont.isActive) t.start(edited, output.absolutePath)
            }
            cont.invokeOnCancellation { handler.post { transformer?.cancel() } }
        }
}

/**
 * Ловит PCM на пути к кодировщику: сводит каналы в моно и пересэмплирует в 16 кГц (линейная
 * интерполяция — Whisper к ней нечувствителен), звук дальше идёт без изменений.
 */
@OptIn(UnstableApi::class)
internal class PcmCaptureProcessor : BaseAudioProcessor() {
    private var channels = 1
    private var inputRate = WHISPER_SAMPLE_RATE
    private val resampler = LinearResampler()
    private var collected = FloatArray(WHISPER_SAMPLE_RATE * 60)
    private var size = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        channels = inputAudioFormat.channelCount
        inputRate = inputAudioFormat.sampleRate
        resampler.reset(inputRate, WHISPER_SAMPLE_RATE)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val source = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = remaining / (2 * channels)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += source.short / 32768f
            mono[f] = sum / channels
        }
        resampler.process(mono) { append(it) }
        // Проход без изменений: кодировщику нужен тот же звук.
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    private fun append(sample: Float) {
        if (size == collected.size) collected = collected.copyOf(collected.size * 2)
        collected[size++] = sample
    }

    fun samples(): FloatArray = collected.copyOf(size)
}

/** Потоковый линейный пересэмплер: состояние переносится между буферами. */
internal class LinearResampler {
    private var step = 1.0
    private var position = 0.0
    private var previous = 0f
    private var primed = false

    fun reset(inputRate: Int, outputRate: Int) {
        step = inputRate.toDouble() / outputRate
        position = 0.0
        primed = false
    }

    /** [position] отсчитывается от сэмпла [previous] (индекс 0; буфер — индексы 1..n). */
    fun process(input: FloatArray, emit: (Float) -> Unit) {
        if (input.isEmpty()) return
        if (!primed) {
            previous = input[0]
            primed = true
            position = 1.0
        }
        // Индекс 0 — previous, индексы 1..n — input; точка n уходит в следующий буфер (без повтора на стыке).
        val n = input.size
        while (position < n) {
            val i = position.toInt()
            val frac = (position - i).toFloat()
            val a = if (i == 0) previous else input[i - 1]
            emit(a + (input[i] - a) * frac)
            position += step
        }
        previous = input[n - 1]
        position -= n
    }
}
