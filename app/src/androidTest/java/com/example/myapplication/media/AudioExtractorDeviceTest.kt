package com.example.myapplication.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.media.player.StreamingPlaybackSessionFactory
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.subtitles.whisper.AudioExtractor
import com.example.myapplication.media.subtitles.whisper.WHISPER_SAMPLE_RATE
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Звук для Whisper на устройстве: речь (jfk.wav, 11 с) по http с локального сервера и кусок
 * публичного тестового HLS. Проверяется длина (16 кГц), что сигнал не пустой и что работа идёт
 * быстрее реального времени.
 */
@RunWith(AndroidJUnit4::class)
class AudioExtractorDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val extractor = AudioExtractor(context, File(context.cacheDir, "whisper-test"))

    private fun rms(x: FloatArray) = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size.coerceAtLeast(1))

    @Test fun speechOverHttpWholeAndClipped() = runBlocking<Unit> {
        val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        val server = WavServer(wav)
        try {
            val video = VetroVideo(url = "http://127.0.0.1:${server.port}/jfk.wav", label = "jfk")
            val factory = StreamingPlaybackSessionFactory.mediaSourceFactory(context, OkHttpClient(), video)
            val item = StreamingPlaybackSessionFactory.buildMediaItem(video)
            val t0 = System.currentTimeMillis()
            val whole = extractor.extract(item, factory, 0, androidx.media3.common.C.TIME_END_OF_SOURCE)
            Log.i(TAG, "jfk whole: ${whole.size} samples (${whole.size / WHISPER_SAMPLE_RATE.toDouble()} s), rms=${rms(whole)}, ${System.currentTimeMillis() - t0} ms")
            assertEquals(11.0, whole.size / WHISPER_SAMPLE_RATE.toDouble(), 0.1)
            assertTrue(rms(whole) > 0.01)
            val clip = extractor.extract(item, factory, 2_000, 5_000)
            Log.i(TAG, "jfk 2-5 s: ${clip.size / WHISPER_SAMPLE_RATE.toDouble()} s")
            assertEquals(3.0, clip.size / WHISPER_SAMPLE_RATE.toDouble(), 0.15)
        } finally {
            server.close()
        }
    }

    @Test fun hlsRangeFasterThanRealTime() = runBlocking<Unit> {
        val video = VetroVideo(url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", label = "hls")
        val factory = StreamingPlaybackSessionFactory.mediaSourceFactory(context, OkHttpClient(), video)
        val t0 = System.currentTimeMillis()
        val pcm = extractor.extract(StreamingPlaybackSessionFactory.buildMediaItem(video), factory, 60_000, 120_000)
        val took = System.currentTimeMillis() - t0
        Log.i(TAG, "hls 60-120 s: ${pcm.size / WHISPER_SAMPLE_RATE.toDouble()} s, rms=${rms(pcm)}, $took ms")
        assertEquals(60.0, pcm.size / WHISPER_SAMPLE_RATE.toDouble(), 1.5)
        assertTrue(rms(pcm) > 0.001)
        assertTrue("минута звука быстрее минуты", took < 60_000)
    }

    private class WavServer(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use {
                        val reader = it.getInputStream().bufferedReader()
                        var range: Long = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range: bytes=", true)) range = line.substringAfter('=').substringBefore('-').toLongOrNull() ?: 0
                        }
                        val out = it.getOutputStream()
                        val slice = body.copyOfRange(range.toInt(), body.size)
                        val head = if (range > 0) {
                            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $range-${body.size - 1}/${body.size}\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\n"
                        }
                        out.write((head + "Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(slice)
                        out.flush()
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
        const val TAG = "AudioExtractorDevice"
    }
}
