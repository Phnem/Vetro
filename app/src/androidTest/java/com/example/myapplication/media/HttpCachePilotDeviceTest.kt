package com.example.myapplication.media

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.media.player.StreamingPlaybackSessionFactory
import com.example.myapplication.media.source.VetroVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Пилот [14]: дисковый кэш прочитанных диапазонов в настоящей фабрике потокового плеера.
 * Нужен `scripts/http-cache-pilot-server.py` на компьютере и `adb reverse tcp:8765 tcp:8765`;
 * без сервера тесты пропускаются. `-e keepCache true` — не стирать кэш (замер после перезапуска).
 */
@RunWith(AndroidJUnit4::class)
class HttpCachePilotDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keepCache = InstrumentationRegistry.getArguments().getString("keepCache") == "true"
    private val client = OkHttpClient()

    @Before fun serverIsUp() {
        assumeTrue("pilot server is not running", runCatching { stats() }.isSuccess)
    }

    @Test fun measure() {
        val dir = File(context.cacheDir, "pilot-measure")
        if (!keepCache) dir.deleteRecursively()
        withCache(dir, 200L * 1024 * 1024) { cache ->
            repeat(5) { run ->
                if (!keepCache) report("none", run, null)
                report("cache", run, cache)
            }
        }
    }

    /** Холодные старты и перемотки вперемешку: без кэша и с пустым кэшем (цена записи на диск). */
    @Test fun coldRuns() {
        repeat(10) { run ->
            report("none-cold", run, null)
            val dir = File(context.cacheDir, "pilot-cold-$run")
            dir.deleteRecursively()
            withCache(dir, 200L * 1024 * 1024) { cache -> report("cache-cold", run, cache) }
            dir.deleteRecursively()
        }
    }

    @Test fun evictionKeepsTheDiskLimit() {
        val dir = File(context.cacheDir, "pilot-evict")
        dir.deleteRecursively()
        // Кэш пишется фрагментами по 5 МБ: при лимите меньше двух фрагментов в нём ничего не остаётся.
        // Перед буфером в 60 с (около 22 МБ) лимит 16 МБ заставляет вытеснять.
        val limit = 16L * 1024 * 1024
        withCache(dir, limit) { cache ->
            play(cache, AtomicLong()) { player ->
                onMain { player.prepare() }
                await(90_000) { onMain { player.bufferedPosition } >= 60_000 }
                // Без этого можно измерить кэш раньше, чем в него попал первый фрагмент.
                await(30_000) { cache.cacheSpace > 0 }
                Log.i(TAG, "eviction while buffering: limit=$limit used=${cache.cacheSpace}")
                assertTrue("disk limit exceeded: ${cache.cacheSpace}", cache.cacheSpace <= limit)
            }
            SystemClock.sleep(1_000)
            Log.i(TAG, "eviction: limit=$limit used=${cache.cacheSpace}")
            assertTrue("disk limit exceeded: ${cache.cacheSpace}", cache.cacheSpace <= limit)
        }
        dir.deleteRecursively()
    }

    @Test fun eachClipReturnsItsOwnBytesAndAMissGoesToTheNetwork() {
        val dir = File(context.cacheDir, "pilot-keys")
        dir.deleteRecursively()
        withCache(dir, 50L * 1024 * 1024) { cache ->
            val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))
            val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val a = "$BASE/clip720.mp4"
            val b = "$BASE/clipB.mp4"
            val directA = read(upstream, a)
            val directB = read(upstream, b)
            assertFalse("the two clips must differ", directA.contentEquals(directB))
            assertArrayEquals(directA, read(cached, a))
            assertArrayEquals(directB, read(cached, b))
            reset()
            assertArrayEquals(directA, read(cached, a))
            assertArrayEquals(directB, read(cached, b))
            Log.i(TAG, "keys: repeat read took ${stats().getLong("bytes")} network bytes")
            // Файлы кэша пропали (очистка системой): чтение должно уйти в сеть и вернуть те же байты.
            dir.walk().filter { it.isFile && it.name.endsWith(".exo") }.forEach { it.delete() }
            reset()
            assertArrayEquals(directA, read(cached, a))
            val afterMiss = stats().getLong("bytes")
            Log.i(TAG, "miss: after deleting cache files $afterMiss network bytes")
            assertTrue("a miss must reach the network", afterMiss > 0)
        }
        dir.deleteRecursively()
    }

    /** Старт, 20 с буфера, перемотка вперёд на 100-ю секунду и назад на 5-ю. */
    private fun report(mode: String, run: Int, cache: SimpleCache?) {
        val fromCache = AtomicLong()
        reset()
        var start = 0L
        var forward = 0L
        var back = 0L
        play(cache, fromCache) { player ->
            start = timeToReady(player) { player.prepare() }
            await(60_000) { onMain { player.bufferedPosition } >= 20_000 }
            forward = timeToReady(player) { player.seekTo(100_000) }
            await(60_000) { onMain { player.bufferedPosition } >= 120_000 }
            back = timeToReady(player) { player.seekTo(5_000) }
            await(60_000) { onMain { player.bufferedPosition } >= 25_000 }
        }
        val stats = stats()
        Log.i(TAG, "run mode=$mode i=$run startMs=$start forwardMs=$forward backMs=$back " +
            "networkBytes=${stats.getLong("bytes")} requests=${stats.getInt("requests")} cacheBytes=${fromCache.get()}")
    }

    private fun play(cache: SimpleCache?, fromCache: AtomicLong, block: (Player) -> Unit) {
        val listener = object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                fromCache.addAndGet(cachedBytesRead)
            }

            override fun onCacheIgnored(reason: Int) = Unit
        }
        val player = onMain {
            StreamingPlaybackSessionFactory.createSession(
                context, client, VetroVideo(url = "$BASE/clip720.mp4", label = "720p"),
                cache = cache, cacheListener = listener,
            ).player.also { it.playWhenReady = false }
        }
        try {
            block(player)
        } finally {
            onMain { player.release() }
        }
    }

    private fun timeToReady(player: Player, action: () -> Unit): Long {
        val began = SystemClock.elapsedRealtime()
        onMain { action() }
        await(60_000) { onMain { player.playbackState } == Player.STATE_READY }
        return SystemClock.elapsedRealtime() - began
    }

    private fun withCache(dir: File, limit: Long, block: (SimpleCache) -> Unit) {
        val cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(limit), StandaloneDatabaseProvider(context))
        try {
            block(cache)
        } finally {
            cache.release()
        }
    }

    private fun read(factory: DataSource.Factory, url: String): ByteArray {
        val source = factory.createDataSource()
        val buffer = ByteArray(128 * 1024)
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(1_000).setLength(buffer.size.toLong()).build())
            var filled = 0
            while (filled < buffer.size) {
                val count = source.read(buffer, filled, buffer.size - filled)
                if (count < 0) break
                filled += count
            }
            return buffer.copyOf(filled)
        } finally {
            source.close()
        }
    }

    private fun stats(): JSONObject = JSONObject(get("/stats"))

    private fun reset() {
        get("/reset")
    }

    private fun get(path: String): String =
        client.newCall(Request.Builder().url(BASE + path).build()).execute().use { it.body.string() }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private companion object {
        const val TAG = "CachePilot"
        const val BASE = "http://127.0.0.1:8765"
    }
}
