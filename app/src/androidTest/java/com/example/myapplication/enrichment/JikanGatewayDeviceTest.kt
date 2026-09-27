package com.example.myapplication.enrichment

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.network.ApiService
import com.example.myapplication.network.JikanGateway
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Jikan на устройстве через боевой клиент: живой ответ или честный отказ без зависания. */
@RunWith(AndroidJUnit4::class)
class JikanGatewayDeviceTest {
    @Test fun liveAnswerOrFastRefusal() = runBlocking<Unit> {
        val koin = GlobalContext.get()
        val probe = koin.get<HttpClient>().get {
            url {
                protocol = io.ktor.http.URLProtocol.HTTPS
                host = "api.jikan.moe"
                appendPathSegments(listOf("v4", "anime", "52991"))
            }
        }
        Log.i("JikanDevice", "probe ${probe.status} ${probe.call.request.url} ${probe.bodyAsText().take(120)}")
        val jikan = JikanGateway(koin.get<HttpClient>())
        val started = System.currentTimeMillis()
        val frieren = jikan.get(listOf("anime", "52991"))
        val took = System.currentTimeMillis() - started
        val title = frieren?.get("data")?.jsonObject?.get("title")?.jsonPrimitive?.content
        Log.i("JikanDevice", "anime/52991 -> $title in ${took}ms, open=${jikan.isOpen}")
        assertTrue("ответ или отказ не дольше таймаута", took < 25_000)
        if (title == null) {
            // Jikan сейчас не отвечает: второй сбой закрывает его, третий вызов — без сети.
            jikan.get(listOf("anime", "52991"))
            val t0 = System.currentTimeMillis()
            jikan.get(listOf("anime", "52991"))
            Log.i("JikanDevice", "closed=${!jikan.isOpen}, skipped call ${System.currentTimeMillis() - t0}ms")
        }
        // Через фасад приложения: MAL по id идёт той же дверью.
        val viaService = koin.get<ApiService>().malById(52991, com.example.myapplication.network.AppLanguage.EN)
        Log.i("JikanDevice", "ApiService.malById -> $viaService")
    }
}
