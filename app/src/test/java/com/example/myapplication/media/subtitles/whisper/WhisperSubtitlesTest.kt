package com.example.myapplication.media.subtitles.whisper

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperSubtitlesTest {

    @Test
    fun `plan starts where the viewer is, then wraps to the beginning, skipping done chunks`() {
        val plan = WhisperSubtitleManager.chunkPlan(durationMs = 250_000, fromMs = 130_000, chunkMs = 60_000, covered = listOf(0L until 60_000L))
        assertEquals(listOf(120_000L until 180_000L, 180_000L until 240_000L, 240_000L until 250_000L, 60_000L until 120_000L), plan)
    }

    @Test
    fun `everything covered means nothing to do`() {
        assertTrue(WhisperSubtitleManager.chunkPlan(120_000, 0, 60_000, listOf(0L until 120_000L)).isEmpty())
        assertTrue(WhisperSubtitleManager.chunkPlan(0, 0, 60_000, emptyList()).isEmpty())
    }

    @Test
    fun `covered ranges merge`() {
        assertEquals(
            listOf(listOf(0L, 180_000L), listOf(240_000L, 300_000L)),
            WhisperSubtitleManager.mergeRanges(listOf(listOf(60_000L, 120_000L), listOf(0L, 60_000L), listOf(240_000L, 300_000L), listOf(120_000L, 180_000L))),
        )
    }

    @Test
    fun `device profile picks a lighter model for weaker phones`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(WhisperModel.SMALL, WhisperDeviceProfile.recommended(12 * gb, 8, arm64 = true))
        assertEquals(WhisperModel.BASE, WhisperDeviceProfile.recommended(4 * gb, 8, arm64 = true))
        assertEquals(WhisperModel.TINY, WhisperDeviceProfile.recommended(2 * gb, 8, arm64 = true))
        assertEquals(WhisperModel.TINY, WhisperDeviceProfile.recommended(12 * gb, 8, arm64 = false))
        assertTrue(WhisperDeviceProfile.isTooHeavy(WhisperModel.SMALL, WhisperModel.BASE))
        assertEquals(6, WhisperDeviceProfile.threads(8))
        assertEquals(1, WhisperDeviceProfile.threads(1))
    }

    private class FakeEngine : Recognizer {
        val languages = mutableListOf<String?>()
        var calls = 0
        override val id = "SMALL"
        override val cloud = false
        override suspend fun transcribeWords(pcm: FloatArray, language: String?, threads: Int) = transcribe(pcm, language, threads) {}
        override suspend fun transcribe(pcm: FloatArray, language: String?, threads: Int, onProgress: (Int) -> Unit): Transcription {
            calls++
            languages += language
            // Реплика на 2-й секунде куска и служебная метка, которую надо выбросить.
            return Transcription("ru", listOf(SubtitleCue(2_000, 4_000, "Реплика ${pcm.size}"), SubtitleCue(5_000, 6_000, "[BLANK_AUDIO]")))
        }
    }

    @Test
    fun `manager shifts cues to video time, caches them and never redoes a chunk`() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("whisper").toFile()
        val engine = FakeEngine()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val manager = WhisperSubtitleManager(
            extract = { _, range -> FloatArray(((range.last + 1 - range.first) / 1000).toInt()) },
            cache = WhisperSubtitleCache(dir),
            scope = scope,
            threads = 4,
        )
        val request = WhisperRequest(
            key = "anime:1:s1:e2:dub", mediaItem = androidx.media3.common.MediaItem.EMPTY,
            sourceFactory = FakeFactory, durationMs = 150_000, fromMs = 70_000,
            language = WhisperLanguage.AUTO, recognizer = engine,
        )
        manager.start(request)
        val done = kotlinx.coroutines.withTimeout(5_000) {
            manager.progress(request.key).first { it?.status == WhisperStatus.DONE }!!
        }
        assertEquals(listOf(2_000L, 62_000L, 122_000L), done.cues.map { it.startMs })
        assertEquals(150_000L, done.coveredMs)
        assertEquals(3, engine.calls)
        // Язык определён на первом куске — дальше распознаётся с ним.
        assertEquals(listOf(null, "ru", "ru"), engine.languages)
        // Повторное открытие: всё из кэша, движок не зовётся.
        assertEquals(WhisperStatus.DONE, manager.cached(request.key, 150_000)?.status)
        manager.start(request)
        kotlinx.coroutines.withTimeout(5_000) { manager.progress(request.key).first { it?.status == WhisperStatus.DONE } }
        assertEquals(3, engine.calls)
        scope.cancel()
    }

    private object FakeFactory : androidx.media3.exoplayer.source.MediaSource.Factory {
        override fun setDrmSessionManagerProvider(p: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(p: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes(): IntArray = intArrayOf()
        override fun createMediaSource(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource = error("unused")
    }
}
