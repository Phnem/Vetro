package com.example.myapplication.media

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.media.player.StreamingPlaybackSessionFactory
import com.example.myapplication.media.source.VetroSubtitleTrack
import com.example.myapplication.media.source.VetroVideo
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Подгруженный пользователем SRT (как из OpenSubtitles) доходит до плеера текстовой дорожкой с тем же
 * id, не включается сам и включается выбором по id — ровно то, что делает меню субтитров плеера.
 */
@RunWith(AndroidJUnit4::class)
class ExternalSubtitleDeviceTest {

    @Test fun userAddedSrtBecomesSelectableTextTrack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.filesDir, "subtitle-test.wav").also(::writeSilence)
        val srt = File(context.cacheDir, "os-42.srt").apply {
            writeText("1\n00:00:00,500 --> 00:00:03,000\nПривет, Vetro\n\n2\n00:00:04,000 --> 00:00:06,000\nВторая строка\n")
        }
        val server = TinyHttpServer(audio.readBytes())
        val video = VetroVideo(
            url = "http://127.0.0.1:${server.port}/audio.wav",
            label = "test",
            subtitles = listOf(
                VetroSubtitleTrack(srt.toURI().toString(), "ru", "application/x-subrip", id = "opensubtitles:42", label = "Русский · test"),
            ),
        )
        // Настоящая сессия стрим-плеера: OkHttp для видео, file:// для подгруженных субтитров.
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = StreamingPlaybackSessionFactory.createSession(context, OkHttpClient(), video).player
            player.prepare()
        }
        try {
            var found: Pair<Int, Int>? = null
            var selectedBefore = true
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (found == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    player.currentTracks.groups.forEachIndexed { g, group ->
                        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                        for (t in 0 until group.length) {
                            if (group.getTrackFormat(t).id?.contains("opensubtitles:42") == true) {
                                found = g to t
                                selectedBefore = group.isTrackSelected(t)
                            }
                        }
                    }
                }
                if (found == null) SystemClock.sleep(100)
            }
            assertTrue("SRT-дорожка появилась в плеере с id", found != null)
            assertFalse("подгруженная дорожка не включается сама", selectedBefore)

            val (g, t) = found!!
            instrumentation.runOnMainSync {
                val group = player.currentTracks.groups[g]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(t)))
                    .build()
            }
            var selected = false
            val until = SystemClock.elapsedRealtime() + 5_000
            while (!selected && SystemClock.elapsedRealtime() < until) {
                instrumentation.runOnMainSync {
                    selected = player.currentTracks.groups.getOrNull(g)?.isTrackSelected(t) == true
                }
                if (!selected) SystemClock.sleep(100)
            }
            assertTrue("выбор по id включает дорожку", selected)

            // Дорожка в списке — ещё не значит, что файл прочитан: играем и ждём первую реплику.
            val cues = java.util.concurrent.CopyOnWriteArrayList<String>()
            instrumentation.runOnMainSync {
                player.addListener(object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) {
                        cueGroup.cues.mapNotNullTo(cues) { it.text?.toString() }
                    }
                })
                player.volume = 0f
                player.play()
            }
            var error: PlaybackException? = null
            val cueDeadline = SystemClock.elapsedRealtime() + 8_000
            while (cues.isEmpty() && error == null && SystemClock.elapsedRealtime() < cueDeadline) {
                instrumentation.runOnMainSync { error = player.playerError }
                SystemClock.sleep(100)
            }
            Log.i("ExternalSubtitleDevice", "cues=$cues error=$error")
            assertNull("плеер без ошибки источника", error)
            assertTrue("реплика из SRT показана", cues.any { it.contains("Привет, Vetro") })
        } finally {
            instrumentation.runOnMainSync { player.release() }
            server.close()
        }
    }

    /** Отдаёт один файл по http на 127.0.0.1 — источнику видео нужен http(s). */
    private class TinyHttpServer(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val out = c.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    private fun writeSilence(file: File) {
        val sampleRate = 8_000
        val count = sampleRate * 8
        val pcmBytes = count * 2
        val bytes = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()); bytes.putInt(36 + pcmBytes)
        bytes.put("WAVEfmt ".toByteArray()); bytes.putInt(16); bytes.putShort(1); bytes.putShort(1)
        bytes.putInt(sampleRate); bytes.putInt(sampleRate * 2); bytes.putShort(2); bytes.putShort(16)
        bytes.put("data".toByteArray()); bytes.putInt(pcmBytes)
        repeat(count) { bytes.putShort(0) }
        file.writeBytes(bytes.array())
    }
}
