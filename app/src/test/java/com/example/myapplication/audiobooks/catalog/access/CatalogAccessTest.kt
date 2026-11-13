package com.example.myapplication.audiobooks.catalog.access

import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogAccessTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private var now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    /** Подставной сервер: запоминает вызовы, ответы задаются сценарием. */
    private class Server {
        val calls = mutableListOf<String>()
        var tokens = 0
        /** null = сеть недоступна (IOException). */
        var challenge: () -> Response? = { null }
        var register: (Request) -> Response = { it.reply(500, "{}") }
        var network: (Request) -> Response = { it.reply(200, "{}") }
    }

    private val server = Server()

    @Before fun setUp() {
        dir = tmp.newFolder("audiobooks")
    }

    /** Подставной сервер стоит ПОСЛЕДНИМ в цепочке: перехватчики из `before` выполняются раньше него. */
    private fun client(vararg before: okhttp3.Interceptor): OkHttpClient = OkHttpClient.Builder().apply { before.forEach { addInterceptor(it) } }.addInterceptor { chain ->
        val req = chain.request()
        server.calls += req.method + " " + req.url.encodedPath
        when (req.url.encodedPath) {
            "/v1/auth/challenge" -> server.challenge() ?: throw IOException("offline")
            "/v1/auth/register" -> server.register(req)
            else -> server.network(req)
        }
    }.build()

    private fun access(attest: (ByteArray) -> List<ByteArray> = { listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)) }) =
        CatalogAccess(dir, client(), "https://api.test/v1", { now }, attest)

    private fun grantTokens() {
        server.challenge = { reply("https://api.test/v1/auth/challenge", 200, """{"challenge":"AQID","expires_in":120}""") }
        server.register = { req ->
            server.tokens++
            req.reply(200, """{"token":"va_t${server.tokens}","expires_at":${now + 30 * day},"install_id":"i"}""")
        }
    }

    @Test fun registersOnceThenReusesTheToken() {
        grantTokens()
        val a = access()
        assertEquals("va_t1", a.token())
        assertEquals("va_t1", a.token())
        assertEquals(listOf("GET /v1/auth/challenge", "POST /v1/auth/register"), server.calls)
    }

    @Test fun refreshesWhenLessThanThreeDaysRemain() {
        grantTokens()
        val a = access()
        assertEquals("va_t1", a.token())
        now += 28 * day // осталось два дня
        assertEquals("va_t2", a.token())
    }

    @Test fun keepsTheOldTokenWhileTheRefreshIsDenied() {
        grantTokens()
        val a = access()
        assertEquals("va_t1", a.token())
        now += 28 * day
        server.register = { it.reply(403, """{"error":"attestation_failed","reason":"signature_not_allowed"}""") }
        assertEquals("va_t1", a.token()) // старый ещё действует: пользователь не теряет каталог
    }

    @Test fun aDeniedRegistrationPausesForTwelveHours() {
        server.challenge = { reply("https://api.test/v1/auth/challenge", 200, """{"challenge":"AQID"}""") }
        server.register = { it.reply(403, """{"error":"attestation_failed","reason":"signature_not_allowed"}""") }
        val a = access()
        assertNull(a.token())
        val calls = server.calls.size
        now += 11 * 60 * 60 * 1000
        assertNull(a.token())
        assertEquals("в паузе сервер не трогаем", calls, server.calls.size)
        grantTokens()
        now += 2 * 60 * 60 * 1000 // пауза кончилась
        assertEquals("va_t1", a.token())
    }

    @Test fun anUnsupportedDeviceNeverCallsRegisterAndWaitsADay() {
        server.challenge = { reply("https://api.test/v1/auth/challenge", 200, """{"challenge":"AQID"}""") }
        val a = access(attest = { throw IllegalStateException("no attestation") })
        assertNull(a.token())
        assertEquals(listOf("GET /v1/auth/challenge"), server.calls)
        now += 23 * 60 * 60 * 1000
        assertNull(a.token())
        assertEquals(1, server.calls.size)
        now += 2 * 60 * 60 * 1000
        assertNull(a.token())
        assertEquals(2, server.calls.size)
    }

    @Test fun noNetworkPausesForTenMinutes() {
        val a = access() // challenge == null: IOException("offline")
        assertNull(a.token())
        now += 5 * 60 * 1000
        assertNull(a.token())
        assertEquals(1, server.calls.size)
        grantTokens()
        now += 6 * 60 * 1000
        assertEquals("va_t1", a.token())
    }

    @Test fun invalidateForgetsTheTokenAndTheStateSurvivesARestart() {
        grantTokens()
        val a = access()
        assertEquals("va_t1", a.token())
        assertEquals("va_t1", access().token()) // новый объект читает файл, сеть не нужна
        assertEquals(2, server.calls.size)
        a.invalidate("va_t1")
        assertEquals("va_t2", a.token())
        a.invalidate("va_other") // чужой токен ничего не сбрасывает
        assertEquals("va_t2", a.token())
    }

    // --- перехватчик ----------------------------------------------------------------------------------------------

    private fun guarded(a: CatalogAccess): OkHttpClient =
        client(CatalogAuthInterceptor(a, "api.test"))

    private fun get(c: OkHttpClient, url: String): Response = c.newCall(Request.Builder().url(url).build()).execute()

    @Test fun theTokenGoesOnlyToTheApiHost() {
        grantTokens()
        val seen = mutableListOf<Pair<String, String?>>()
        server.network = { req -> seen += req.url.host to req.header("Authorization"); req.reply(200, "{}") }
        val c = guarded(access())
        get(c, "https://api.test/v1/search?q=a").close()
        get(c, "https://other.example/x").close()
        assertEquals(listOf("api.test" to "Bearer va_t1", "other.example" to null), seen)
    }

    @Test fun withoutATokenTheCallGoesAnonymousAndTheCallerFallsBack() {
        var header: String? = "unset"
        server.network = { req -> header = req.header("Authorization"); req.reply(401, """{"error":"missing_key"}""") }
        // challenge недоступен: токена нет
        val resp = get(guarded(access()), "https://api.test/v1/search?q=a")
        assertEquals(401, resp.code)
        assertNull(header)
        resp.close()
    }

    @Test fun aRejectedTokenIsReplacedAndTheCallRepeatedOnce() {
        grantTokens()
        val used = mutableListOf<String?>()
        server.network = { req ->
            used += req.header("Authorization")
            if (req.header("Authorization") == "Bearer va_t1") req.reply(401, """{"error":"invalid_key"}""") else req.reply(200, "{}")
        }
        val resp = get(guarded(access()), "https://api.test/v1/search?q=a")
        assertEquals(200, resp.code)
        assertEquals(listOf("Bearer va_t1", "Bearer va_t2"), used)
        resp.close()
    }

    @Test fun aRevokedInstallIsTreatedLikeARejectedTokenButATierRefusalIsNot() {
        grantTokens()
        var first = true
        server.network = { req ->
            if (first) { first = false; req.reply(403, """{"error":"install_revoked"}""") } else req.reply(200, "{}")
        }
        assertEquals(200, get(guarded(access()), "https://api.test/v1/manifest").also { it.close() }.code)
        var calls = 0
        server.network = { req -> calls++; req.reply(403, """{"error":"tier_forbids_route"}""") }
        assertEquals(403, get(guarded(access()), "https://api.test/v1/manifest").also { it.close() }.code)
        assertEquals(1, calls)
    }

    @Test fun registrationRequestsNeverCarryTheToken() {
        grantTokens()
        var registerHeader: String? = "unset"
        server.register = { req ->
            registerHeader = req.header("Authorization")
            server.tokens++
            req.reply(200, """{"token":"va_t1","expires_at":${now + 30 * day},"install_id":"i"}""")
        }
        get(guarded(access()), "https://api.test/v1/search?q=a").close()
        assertNull(registerHeader)
    }
}

private fun Request.reply(code: Int, body: String): Response = reply(url.toString(), code, body, this)

private fun reply(url: String, code: Int, body: String, request: Request = Request.Builder().url(url).build()): Response =
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("m")
        .body(body.toResponseBody("application/json".toMediaType())).build()
