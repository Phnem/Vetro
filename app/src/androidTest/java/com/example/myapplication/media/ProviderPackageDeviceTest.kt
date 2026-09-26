package com.example.myapplication.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.custom.CustomSourceInstaller
import com.example.myapplication.media.source.movieseries.custom.CustomSourceOutcome
import com.example.myapplication.media.source.movieseries.custom.CustomSourceRegistry
import com.example.myapplication.media.source.movieseries.custom.CustomSourceSettingsService
import com.example.myapplication.media.source.sdk.PackageIntegrity
import com.example.myapplication.media.source.sdk.PackageOrigin
import com.example.myapplication.media.source.sdk.PackageResult
import com.example.myapplication.media.source.sdk.ProviderPackage
import com.example.myapplication.media.source.sdk.ProviderPackageRuntime
import com.example.myapplication.media.source.sdk.RuntimeLimits
import com.example.myapplication.network.AppJson
import com.google.crypto.tink.subtle.Ed25519Sign
import io.ktor.client.HttpClient
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named

/**
 * Provider SDK на устройстве: импорт подписанного пакета через настоящий сервис и хранилище (с
 * просмотром и подтверждением), резолв серии через реестр и боевой клиент пакетов, песочница на живых
 * сокетах — редирект за белый список и медленный сервер. Пакет вымышленный, сервер — на 127.0.0.1.
 */
@RunWith(AndroidJUnit4::class)
class ProviderPackageDeviceTest {
    private val koin get() = GlobalContext.get()

    @Test fun importResolveAndSandbox() = runBlocking<Unit> {
        val server = LocalServer()
        val base = "http://127.0.0.1:${server.port}"
        val keys = Ed25519Sign.KeyPair.newKeyPair()
        val text = signedPackage(base, keys)
        val service = koin.get<CustomSourceSettingsService>()
        val key = CustomSourceInstaller.packageKey("device.test")
        try {
            // 1. Импорт: сначала только просмотр, в хранилище пусто; затем подтверждение.
            val review = service.installFromText(text) as CustomSourceOutcome.ReviewRequired
            assertTrue(service.summaries().none { it.key == key })
            assertEquals(listOf("127.0.0.1"), review.preview.hosts)
            assertTrue(review.preview.origin is PackageOrigin.Signed)
            Log.i(TAG, "preview: ${review.preview.name} ${review.preview.version} sha=${review.preview.sha256.take(16)}")
            assertTrue(service.confirm(review.preview) is CustomSourceOutcome.Installed)

            // 2. Серия через реестр каскада: поиск по tmdb → серии → потоки; чужой хост отброшен.
            val provider = koin.get<CustomSourceRegistry>().providers().first { it.id.value == "package:device.test" }
            val request = PlaybackRequest(
                anime = Anime(id = "d", title = "Show", episodes = 10, rating = 0f, imageFileName = null, orderIndex = 0,
                    dateAdded = 0, mediaType = MediaType.SERIES, tmdbId = 1396),
                episodeNumber = 2,
                seasonInfo = SeasonInfo(seasonNumber = 1, episodes = 10, source = "test"),
            )
            val found = provider.resolve(request)
            Log.i(TAG, "resolve: $found")
            val videos = (found as ProviderResolution.Found).hosters.single().videos.orEmpty()
            assertEquals(listOf("$base/media/e2.m3u8"), videos.map { it.url })

            // 3. Обновление, подписанное чужим ключом, отклоняется.
            val foreign = signedPackage(base, Ed25519Sign.KeyPair.newKeyPair(), version = "1.1.0")
            val rejected = service.installFromText(foreign)
            Log.i(TAG, "foreign update: $rejected")
            assertTrue(rejected is CustomSourceOutcome.Rejected)

            // 4. Песочница на живых сокетах: редирект наружу и медленный сервер.
            val pkg = AppJson.decodeFromString(ProviderPackage.serializer(), text)
            val client = koin.get<HttpClient>(named("provider-packages"))
            val runtime = ProviderPackageRuntime(pkg, client, limits = RuntimeLimits(operationTimeoutMs = 2_000))
            val redirected = runtime.streams(unitId = "redirect")
            Log.i(TAG, "redirect: $redirected")
            assertTrue(redirected is PackageResult.Blocked)
            val started = System.currentTimeMillis()
            val slow = runtime.streams(unitId = "slow")
            val took = System.currentTimeMillis() - started
            Log.i(TAG, "slow: $slow in ${took}ms")
            assertTrue(slow is PackageResult.Failed)
            assertTrue("таймаут операции соблюдён", took < 5_000)
            assertTrue("медленный сервер не получил ключа наружу и не был обойдён", server.requests.none { it.contains("example.org") })
        } finally {
            service.remove(key)
            server.close()
        }
    }

    /** Манифест v1 на Android: шаблон плейсхолдера раньше не компилировался в ICU-регэкспе. */
    @Test fun legacyManifestValidatesOnAndroid() {
        val result = CustomSourceInstaller().fromManifestJson(
            """{"manifestVersion":1,"id":"legacy","name":"Legacy","baseUrl":"https://api.example.com",
               "capabilities":["MOVIE","TMDB_ID"],"movie":{"path":"/movie/{tmdbId}"},
               "response":{"streams":"/streams","url":"/url"}}""",
        )
        Log.i(TAG, "legacy manifest: $result")
        assertTrue(result is com.example.myapplication.media.source.movieseries.custom.SourceInstallResult.Installed)
    }

    private fun signedPackage(base: String, keys: Ed25519Sign.KeyPair, version: String = "1.0.0"): String {
        val body = """
            {"format":2,"id":"device.test","version":"$version","sdk":{"min":2,"max":2},"name":"Device Test",
             "mediaTypes":["SERIES"],"capabilities":["SEARCH_BY_EXTERNAL_ID","UNITS","STREAMS"],"externalIds":["tmdb"],
             "allowedHosts":["127.0.0.1"],"allowInsecureHttp":true,
             "operations":{
               "search":{"request":{"url":"$base/search?tmdb={tmdbId}"},"items":"/results","id":"/id","title":"/title","externalIds":{"tmdb":"/tmdb"}},
               "units":{"request":{"url":"$base/titles/{titleId}/episodes"},"items":"/episodes","id":"/id","number":"/number","season":"/season"},
               "streams":{"request":{"url":"$base/episodes/{unitId}/streams"},"items":"/streams","url":"/url","label":"/label"}}}
        """.trimIndent()
        val root = AppJson.parseToJsonElement(body).jsonObject
        val signature = Ed25519Sign(keys.privateKey).sign(PackageIntegrity.canonical(root).toByteArray())
        val signed = JsonObject(root + ("signature" to buildJsonObject {
            put("alg", "ed25519")
            put("publicKey", Base64.getEncoder().encodeToString(keys.publicKey))
            put("value", Base64.getEncoder().encodeToString(signature))
        }))
        return AppJson.encodeToString(JsonObject.serializer(), signed)
    }

    /** Мини-сервер пакета: поиск, серии, потоки, редирект наружу и «зависающий» ответ. */
    private class LocalServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(client) }
            }
        }

        private fun handle(c: Socket) = c.use {
            val reader = c.getInputStream().bufferedReader()
            val line = reader.readLine() ?: return
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val path = line.split(' ').getOrElse(1) { "/" }
            requests += path
            val out = c.getOutputStream()
            fun send(status: String, body: String, extra: String = "") {
                val bytes = body.toByteArray()
                out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                out.write(bytes)
                out.flush()
            }
            when {
                path.startsWith("/search") -> send("200 OK", """{"results":[{"id":"t1","title":"Show","tmdb":"1396"}]}""")
                path == "/titles/t1/episodes" -> send("200 OK", """{"episodes":[{"id":"e1","number":1,"season":1},{"id":"e2","number":2,"season":1}]}""")
                path == "/episodes/e2/streams" -> send(
                    "200 OK",
                    """{"streams":[{"url":"http://127.0.0.1:$port/media/e2.m3u8","label":"1080p"},{"url":"https://cdn.example.org/e2.m3u8","label":"x"}]}""",
                )
                path == "/episodes/redirect/streams" -> send("302 Found", "", "Location: https://example.org/steal\r\n")
                path == "/episodes/slow/streams" -> {
                    // Заголовки и начало тела — и тишина: песочница обязана уйти по таймауту.
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n{\"streams\":[".toByteArray())
                    out.flush()
                    Thread.sleep(8_000)
                }
                else -> send("404 Not Found", "{}")
            }
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }

    private companion object {
        const val TAG = "ProviderPackageDevice"
    }
}
