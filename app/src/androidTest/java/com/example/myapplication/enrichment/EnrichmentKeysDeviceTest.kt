package com.example.myapplication.enrichment

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.data.remote.AndroidGoogleApiIdentity
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.enrichment.EnrichmentHttp
import com.example.myapplication.network.enrichment.FanartClient
import com.example.myapplication.network.enrichment.GoogleApiIdentity
import com.example.myapplication.network.enrichment.GoogleBooksClient
import com.example.myapplication.network.enrichment.NytBooksClient
import com.example.myapplication.network.enrichment.TasteDiveClient
import com.example.myapplication.network.enrichment.TasteType
import com.example.myapplication.network.enrichment.YouTubeClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Ключи из local.properties на устройстве. Ключ Google ограничен пакетом com.phnem.vetro: тестовая
 * сборка .perf подставляет этот пакет, а SHA-1 сертификата считает настоящий — тем самым проверяется
 * AndroidGoogleApiIdentity. Свежий EnrichmentHttp без кэша и выключателя — ответы живые.
 */
@RunWith(AndroidJUnit4::class)
class EnrichmentKeysDeviceTest {
    private val http get() = EnrichmentHttp(GlobalContext.get().get<HttpClient>())
    private fun rate() = TokenBucketRateLimiter(maxTokens = 5.0, refillTokensPerSecond = 5.0)
    private fun log(msg: String) = Log.i("EnrichmentKeys", msg)

    private val identity: GoogleApiIdentity by lazy {
        val real = AndroidGoogleApiIdentity(InstrumentationRegistry.getInstrumentation().targetContext).headers()
        log("identity: package=${real["X-Android-Package"]} cert=${real["X-Android-Cert"]?.take(8)}…")
        GoogleApiIdentity { real + ("X-Android-Package" to "com.phnem.vetro") }
    }

    @Test fun googleKeyWithAppIdentity() = runBlocking<Unit> {
        val yt = YouTubeClient(http, rate(), identity = identity).videos(listOf("tR8YH0G67Rk"))
        log("youtube: $yt")
        assertTrue(yt is LookupResult.Found)
        val books = GoogleBooksClient(http, rate(), identity = identity).byIsbn("9785041619015")
        log("books: $books")
        assertTrue(books is LookupResult.Found || books is LookupResult.NoMatch)
        // Без заголовков приложения ключ не пускают — это и есть ограничение по пакету.
        val blocked = YouTubeClient(http, rate()).videos(listOf("tR8YH0G67Rk"))
        log("youtube without identity: $blocked")
        assertTrue(blocked is LookupResult.Failure)
    }

    @Test fun otherKeys() = runBlocking<Unit> {
        val fanart = FanartClient(http, rate()).tv(81189)
        log("fanart: ${(fanart as? LookupResult.Found)?.value?.logos?.size} logos")
        assertTrue(fanart is LookupResult.Found)
        val nyt = NytBooksClient(http, rate()).overview()
        log("nyt: ${(nyt as? LookupResult.Found)?.value?.map { it.name }?.take(3)}")
        assertTrue(nyt is LookupResult.Found)
        val taste = TasteDiveClient(http, rate()).similar("Breaking Bad", TasteType.SHOW, TasteType.SHOW, limit = 5)
        log("tastedive: $taste")
    }

    @Test fun realCertificateIsTheDebugOne() {
        val headers = AndroidGoogleApiIdentity(InstrumentationRegistry.getInstrumentation().targetContext).headers()
        assertEquals(40, headers["X-Android-Cert"]?.length)
    }
}
