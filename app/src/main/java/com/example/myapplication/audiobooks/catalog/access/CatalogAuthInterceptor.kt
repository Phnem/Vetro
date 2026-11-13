package com.example.myapplication.audiobooks.catalog.access

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Подставляет токен установки в запросы к API каталога (и только к нему: чужие хосты не видят токен). Сами запросы регистрации
 * (`/v1/auth/…`) идут без токена. Если сервер не принял токен (401, либо 403 «установка отозвана»), он забывается, регистрация проходит заново
 * и запрос повторяется один раз; не вышло — запрос уходит без токена и вызывающий код получает отказ, после которого идёт по прежним источникам.
 */
class CatalogAuthInterceptor(
    private val access: CatalogAccess,
    private val host: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != host || request.url.encodedPath.startsWith("/v1/auth/")) return chain.proceed(request)
        val token = access.token() ?: return chain.proceed(request)
        val response = chain.proceed(request.withToken(token))
        if (!rejected(response)) return response
        response.close()
        access.invalidate(token)
        val fresh = access.token() ?: return chain.proceed(request)
        return chain.proceed(request.withToken(fresh))
    }

    private fun rejected(response: Response): Boolean =
        response.code == 401 || (response.code == 403 && response.peekBody(PEEK_BYTES).string().contains("install_revoked"))

    private fun Request.withToken(token: String): Request = newBuilder().header("Authorization", "Bearer $token").build()

    private companion object {
        const val PEEK_BYTES = 256L
    }
}
