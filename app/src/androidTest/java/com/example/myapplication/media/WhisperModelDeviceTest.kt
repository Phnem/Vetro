package com.example.myapplication.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Загрузка модели Whisper на телефоне: самая лёгкая (tiny, 32 МБ) — обрыв посреди загрузки,
 * докачка с места обрыва, проверка SHA-256, удаление. Каталог тестовый, после теста пуст.
 */
@RunWith(AndroidJUnit4::class)
class WhisperModelDeviceTest {
    @Test fun downloadResumeVerifyDelete() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "whisper-device-test").apply { deleteRecursively() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = WhisperModelStore(dir, OkHttpClient(), scope)
        val model = WhisperModel.TINY
        try {
            // «Оборванная» загрузка: первые 5 МБ уже лежат в .part.
            dir.mkdirs()
            val head = OkHttpClient().newCall(
                okhttp3.Request.Builder().url(model.url).header("Range", "bytes=0-${5_000_000 - 1}").build(),
            ).execute().use { r -> Log.i(TAG, "head HTTP ${r.code}"); r.body!!.bytes() }
            File(dir, model.fileName + ".part").writeBytes(head)
            Log.i(TAG, "partial ${head.size} bytes")
            assertEquals(5_000_000, head.size)

            val t0 = System.currentTimeMillis()
            store.download(model)
            val done = withTimeout(180_000) { store.states.first { it[model] is ModelState.Ready || it[model] is ModelState.Failed }[model] }
            Log.i(TAG, "finished: $done in ${System.currentTimeMillis() - t0} ms")
            assertTrue(done is ModelState.Ready)
            assertEquals(model.sizeBytes, (done as ModelState.Ready).file.length())

            // Новый экземпляр видит готовую модель без повторной загрузки.
            assertTrue(WhisperModelStore(dir, OkHttpClient(), scope).readyFile(model) != null)

            store.delete(model)
            assertTrue(store.states.value[model] is ModelState.Absent)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "WhisperModelDevice"
    }
}
