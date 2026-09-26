package com.example.myapplication.media.source

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class KtorPlaybackSourceConnectionTester(
    private val webDavClient: HttpClient,
    private val personalServerClient: HttpClient,
    /** Проверка учёток — настоящим входом в сервис; ключ: вид учётки. */
    private val accountTesters: Map<UserAccountKind, suspend (UserAccountConfig) -> Boolean> = emptyMap(),
) : PlaybackSourceConnectionTester {
    override suspend fun testAccount(kind: UserAccountKind, config: UserAccountConfig): Boolean = safeTest {
        config.isValidFor(kind) && accountTesters[kind]?.invoke(config) == true
    }

    override suspend fun testWebDav(config: WebDavConfig): Boolean = safeTest {
        if (!config.isValid()) return@safeTest false
        val url = config.baseUrl.trim().trimEnd('/') + "/" + config.rootPath.trim().trim('/')
        val response = webDavClient.request(url) {
            method = HttpMethod("PROPFIND")
            header(HttpHeaders.Authorization, config.authorizationHeader())
            header("Depth", "0")
        }
        response.status == HttpStatusCode.MultiStatus || response.status.isSuccess()
    }

    override suspend fun testPersonalServer(
        provider: PersonalMediaServerProvider,
        config: PersonalMediaServerConfig,
    ): Boolean = safeTest {
        if (!config.isValid()) return@safeTest false
        val response = personalServerClient.get(
            config.baseUrl.trim().trimEnd('/') + "/Users/${config.userId}"
        ) {
            header("X-Emby-Token", config.accessToken)
        }
        response.status.isSuccess()
    }

    private suspend fun safeTest(block: suspend () -> Boolean): Boolean = try {
        withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { block() } ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 10_000L
    }
}
