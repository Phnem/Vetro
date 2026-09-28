package com.example.myapplication.audiobooks.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Насколько сжимать паузы между фразами. Не ускорение всей книги: речь идёт с выбранной скоростью,
 * укорачиваются только паузы — длинные сильнее, короткие не трогаются вовсе.
 */
enum class SilenceLevel(
    /** Пауза короче — не трогаем (микросекунды). */
    val minimumSilenceUs: Long,
    /** Какая доля паузы остаётся. */
    val retentionRatio: Float,
    /** Больше этого от паузы не остаётся никогда (микросекунды). */
    val maxKeptUs: Long,
) {
    OFF(0, 1f, 0),
    LIGHT(minimumSilenceUs = 300_000, retentionRatio = 0.6f, maxKeptUs = 1_500_000),
    NORMAL(minimumSilenceUs = 200_000, retentionRatio = 0.35f, maxKeptUs = 800_000),
    AGGRESSIVE(minimumSilenceUs = 100_000, retentionRatio = 0.15f, maxKeptUs = 300_000);

    companion object {
        fun fromOrdinal(value: Int): SilenceLevel = entries.getOrElse(value) { OFF }
    }
}

/**
 * Цепочка обработки звука плеера аудиокниг: три заранее настроенных обработчика тишины (по одному
 * на уровень) и Sonic для скорости. Включён не больше одного обработчика — выбранного уровня.
 *
 * Параметры `SilenceSkippingAudioProcessor` задаются только в конструкторе, а цепочку плеер
 * получает один раз при создании аудиовыхода: сменить параметры на лету нельзя, поэтому уровни —
 * это готовые экземпляры, а смена уровня — переключение `skipSilenceEnabled` у плеера (выход
 * при этом перечитывает, какой обработчик активен).
 */
@UnstableApi
class LeveledSilenceChain : AudioProcessorChain {
    private val byLevel = SilenceLevel.entries.filter { it != SilenceLevel.OFF }.associateWith { level ->
        SilenceSkippingAudioProcessor(
            level.minimumSilenceUs,
            level.retentionRatio,
            level.maxKeptUs,
            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
        )
    }
    private val sonic = SonicAudioProcessor()
    private val processors: Array<AudioProcessor> = (byLevel.values + sonic).toTypedArray()

    /** Уровень, который включится при следующем `applySkipSilenceEnabled(true)`. */
    @Volatile
    var level: SilenceLevel = SilenceLevel.OFF

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        val active = level.takeIf { skipSilenceEnabled && it != SilenceLevel.OFF }
        byLevel.forEach { (lvl, processor) -> processor.setEnabled(lvl == active) }
        return active != null
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = byLevel.values.sumOf { it.skippedFrames }
}
