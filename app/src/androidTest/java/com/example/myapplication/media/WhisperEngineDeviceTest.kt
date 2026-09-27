package com.example.myapplication.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.media.player.StreamingPlaybackSessionFactory
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.subtitles.whisper.AudioExtractor
import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.WHISPER_SAMPLE_RATE
import com.example.myapplication.media.subtitles.whisper.WhisperCppEngine
import com.example.myapplication.media.subtitles.whisper.WhisperDeviceProfile
import com.example.myapplication.media.subtitles.whisper.WhisperLanguage
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import com.example.myapplication.media.subtitles.whisper.WhisperRequest
import com.example.myapplication.media.subtitles.whisper.WhisperStatus
import com.example.myapplication.media.subtitles.whisper.WhisperSubtitleCache
import com.example.myapplication.media.subtitles.whisper.WhisperSubtitleManager
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * whisper.cpp на телефоне: английская речь (jfk.wav), русская речь (синтез TTS телефона), скорость
 * tiny/small и вся цепочка «звук видео → куски → реплики». Модели скачиваются в тестовый каталог.
 */
@RunWith(AndroidJUnit4::class)
class WhisperEngineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelsDir = File(context.filesDir, "whisper-engine-test")
    private val engine = WhisperCppEngine()
    private val threads = WhisperDeviceProfile.threads(Runtime.getRuntime().availableProcessors())

    private suspend fun model(model: WhisperModel): File {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = WhisperModelStore(modelsDir, OkHttpClient(), scope)
            store.readyFile(model)?.let { return it }
            store.download(model)
            val state = withTimeout(600_000) { store.states.first { it[model] is ModelState.Ready || it[model] is ModelState.Failed }[model] }
            return (state as ModelState.Ready).file
        } finally {
            scope.cancel()
        }
    }

    private fun wavToFloats(bytes: ByteArray): FloatArray {
        // 44-байтный заголовок PCM WAV, 16 бит моно 16 кГц.
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - 44) / 2) { data.short / 32768f }
    }

    @Test fun englishSpeechTinyAndSmall() = runBlocking<Unit> {
        assertTrue("libvetro_whisper.so загружается", engine.isAvailable)
        val pcm = wavToFloats(InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() })
        for (m in listOf(WhisperModel.TINY, WhisperModel.SMALL)) {
            val file = model(m)
            val t0 = System.currentTimeMillis()
            val result = engine.transcribe(file, pcm, null, threads) {}
            val ms = System.currentTimeMillis() - t0
            val text = result.cues.joinToString(" ") { it.text }
            Log.i(TAG, "$m (threads=$threads): ${ms} ms for 11 s audio (RTF ${"%.2f".format(ms / 11_000.0)}), lang=${result.language}: $text | cues=${result.cues.map { it.startMs to it.endMs }}")
            assertTrue(text.lowercase().contains("country"))
            assertTrue(result.language == "en")
        }
    }

    /**
     * Русская речь: начало записи LibriVox «Предложение» Чехова (общественное достояние) — первые
     * 30 секунд, чтение прямо с archive.org тем же извлекателем, что и звук видео.
     */
    @Test fun russianSpeechLibriVox() = runBlocking<Unit> {
        val video = VetroVideo(url = "https://archive.org/download/predlozhenie_1404_librivox/predlozhenie_01_chekhov_64kb.mp3", label = "ru")
        val pcm = AudioExtractor(context, File(context.cacheDir, "whisper-ru")).extract(
            StreamingPlaybackSessionFactory.buildMediaItem(video),
            StreamingPlaybackSessionFactory.mediaSourceFactory(context, OkHttpClient(), video),
            0, 30_000,
        )
        Log.i(TAG, "ru audio: ${pcm.size / WHISPER_SAMPLE_RATE.toDouble()} s")
        for (m in listOf(WhisperModel.SMALL, WhisperModel.BASE)) {
            val file = model(m)
            val t0 = System.currentTimeMillis()
            val auto = engine.transcribe(file, pcm, null, threads) {}
            val ms = System.currentTimeMillis() - t0
            val text = auto.cues.joinToString(" ") { it.text }
            Log.i(TAG, "RU $m auto: $ms ms for 30 s (RTF ${"%.2f".format(ms / 30_000.0)}), lang=${auto.language}: $text")
            assertTrue(auto.language == "ru")
            assertTrue(text.count { it in 'а'..'я' || it in 'А'..'Я' } > 40)
        }
    }

    @Test fun wholeChainOverHttp() = runBlocking<Unit> {
        val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        val server = Server(wav)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val video = VetroVideo(url = "http://127.0.0.1:${server.port}/jfk.wav", label = "jfk")
            val extractor = AudioExtractor(context, File(context.cacheDir, "whisper-chain"))
            val cacheDir = File(context.cacheDir, "whisper-chain-subs").apply { deleteRecursively() }
            val manager = WhisperSubtitleManager(
                extract = { r, range -> extractor.extract(r.mediaItem, r.sourceFactory, range.first, range.last + 1) },
                engine = engine,
                cache = WhisperSubtitleCache(cacheDir),
                scope = scope,
                threads = threads,
            )
            val request = WhisperRequest(
                key = "device-test|jfk", mediaItem = StreamingPlaybackSessionFactory.buildMediaItem(video),
                sourceFactory = StreamingPlaybackSessionFactory.mediaSourceFactory(context, OkHttpClient(), video),
                durationMs = 11_000, fromMs = 0, language = WhisperLanguage.EN,
                model = WhisperModel.TINY, modelFile = model(WhisperModel.TINY),
            )
            manager.start(request)
            val done = withTimeout(120_000) { manager.progress(request.key).first { it?.status == WhisperStatus.DONE || it?.status == WhisperStatus.FAILED }!! }
            Log.i(TAG, "chain: ${done.status}, covered=${done.coveredMs}, cues=${done.cues}")
            assertTrue(done.status == WhisperStatus.DONE)
            assertTrue(done.cues.joinToString(" ") { it.text }.lowercase().contains("country"))
            assertTrue("реплики — в пределах видео", done.cues.all { it.startMs in 0..11_000 })
        } finally {
            scope.cancel()
            server.close()
        }
    }

    private class Server(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use {
                        val reader = it.getInputStream().bufferedReader()
                        var start = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range: bytes=", true)) start = line.substringAfter('=').substringBefore('-').toIntOrNull() ?: 0
                        }
                        val slice = body.copyOfRange(start, body.size)
                        val head = if (start > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${body.size - 1}/${body.size}\r\n" else "HTTP/1.1 200 OK\r\n"
                        it.getOutputStream().apply {
                            write((head + "Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(slice)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }

    private companion object {
        const val TAG = "WhisperEngineDevice"
    }
}
