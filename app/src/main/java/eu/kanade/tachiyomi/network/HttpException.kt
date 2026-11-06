package eu.kanade.tachiyomi.network

class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
